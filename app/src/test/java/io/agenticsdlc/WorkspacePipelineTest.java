package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.support.TestRepos;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M2 end to end: real checkout and Docker sandbox for context preparation and verification, placeholder stages for
 * the rest. Repositories are local git repos reached through mirror rules, so no network is needed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "agentic.stub-stages.enabled=true", "agentic.sandbox.enabled=true",
		"agentic.sandbox.network=none", "agentic.sandbox.memory=256MB", "agentic.worker.poll-interval=50ms",
		"agentic.limits.max-fix-iterations=1", "agentic.agent.enabled=false" })
class WorkspacePipelineTest {

	private static final Path ROOT;
	private static final String GOOD = "https://github.com/acme/good.git";
	private static final String BROKEN = "https://github.com/acme/broken.git";

	static {
		try {
			ROOT = Files.createTempDirectory("agentic-pipeline");
			TestRepos.demoRepo(ROOT.resolve("good"));
			TestRepos.createRepo(ROOT.resolve("broken"), Map.of(".agentic-sdlc.yml",
					"image: " + TestRepos.ALPINE + "\nbuild: 'true'\ntest: echo 'expected bye' && exit 1\n"));
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.sandbox.workspace-root", () -> ROOT.resolve("workspaces").toString());
		registry.add("agentic.scm.mirrors[" + GOOD + "]", () -> ROOT.resolve("good").toUri().toString());
		registry.add("agentic.scm.mirrors[" + BROKEN + "]", () -> ROOT.resolve("broken").toUri().toString());
	}

	@Autowired
	TaskIntake intake;

	@Autowired
	RunQueries queries;

	@Autowired
	Sandbox sandbox;

	private UUID runId;

	@AfterEach
	void cleanUp() {
		if (runId != null) {
			sandbox.destroy(runId).block();
		}
	}

	private UUID submit(String url) {
		NewTask task = new NewTask(TaskOrigin.PROMPT, null, "[low] demo", "demo task",
				new RepositoryRef(ScmKind.GITHUB, URI.create(url)), null, "tester", null);
		runId = intake.submit(task).block().view().run().id();
		return runId;
	}

	private Run await(UUID id, Predicate<Run> condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 120_000;
		Run run = queries.get(id).block().run();
		while (!condition.test(run)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out, last state " + run.state() + "; events: " + events(id));
			}
			Thread.sleep(100);
			run = queries.get(id).block().run();
		}
		return run;
	}

	private List<RunEvent> events(UUID id) {
		return queries.events(id, 0, 500).collectList().block();
	}

	@Test
	void buildsAndVerifiesInTheSandboxThenWaitsForPublishApproval() throws Exception {
		UUID id = submit(GOOD);
		Run run = await(id, r -> r.state() == RunState.AWAITING_APPROVAL || r.state().isTerminal()
				|| r.state() == RunState.NEEDS_HUMAN);

		assertThat(run.pendingGate()).as("events: %s", events(id)).isEqualTo(Gate.PUBLISH);
		List<RunEvent> log = events(id);
		assertThat(log).filteredOn(e -> e.type() == RunEventType.COMMAND_OUTPUT)
				.extracting(e -> e.payload().get("command"))
				.containsExactly("test -f hello.txt", "test -f hello.txt", "grep -q hello hello.txt");
		assertThat(log).filteredOn(e -> e.type() == RunEventType.STAGE_COMPLETED
				&& "PREPARING_CONTEXT".equals(e.payload().get("stage")))
				.singleElement().satisfies(e -> assertThat(String.valueOf(e.payload().get("detail")))
						.contains("baselineBuild=PASSED", "tool=custom"));
	}

	@Test
	void failingTestsLoopBackThenEscalateWithTheFailureOutput() throws Exception {
		UUID id = submit(BROKEN);
		Run run = await(id, r -> r.state() == RunState.NEEDS_HUMAN || r.state().isTerminal());

		assertThat(run.state()).isEqualTo(RunState.NEEDS_HUMAN);
		assertThat(run.resumeState()).isEqualTo(RunState.VERIFYING);
		assertThat(run.fixIterations()).isEqualTo(1);
		assertThat(events(id)).filteredOn(e -> e.type() == RunEventType.ERROR).last()
				.satisfies(e -> assertThat(String.valueOf(e.payload().get("reason")))
						.contains("verification still failing").contains("expected bye"));
	}
}
