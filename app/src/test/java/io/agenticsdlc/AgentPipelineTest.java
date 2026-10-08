package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.adapter.out.docker.SandboxJanitor;
import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.agent.AgentModel;
import io.agenticsdlc.core.agent.AgentModels;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.ModelReply;
import io.agenticsdlc.core.agent.ModelRequest;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.support.TestRepos;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * M4 end to end with every real stage (agent stages, sandbox, checkout); only the LLM is scripted. A MEDIUM run:
 * triage → spec → SPEC gate → implement (real edit in the sandbox) → verify (real tests) → review → PUBLISH gate.
 */
@Import({ TestcontainersConfiguration.class, AgentPipelineTest.ScriptedModels.class })
@SpringBootTest(properties = { "agentic.sandbox.enabled=true", "agentic.agent.enabled=true",
		"agentic.stub-stages.enabled=false", "agentic.sandbox.network=none", "agentic.sandbox.memory=256MB",
		"agentic.worker.poll-interval=50ms" })
class AgentPipelineTest {

	private static final Path ROOT;
	private static final String REPO = "https://github.com/acme/agent-demo.git";

	static {
		try {
			ROOT = Files.createTempDirectory("agentic-agent-pipeline");
			// Like the demo repository, but its test command also runs the shell tests under tests/.
			TestRepos.createRepo(ROOT.resolve("repo"), Map.of(
					".agentic-sdlc.yml", "image: " + TestRepos.ALPINE + "\nbuild: test -f hello.txt\n"
							+ "test: grep -q hello hello.txt && for t in tests/*.sh; do [ -e \"$t\" ] || continue; sh \"$t\" || exit 1; done\n",
					"hello.txt", "hello world\n",
					"AGENTS.md", "# Agent notes\nKeep it simple.\n"));
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.sandbox.workspace-root", () -> ROOT.resolve("workspaces").toString());
		registry.add("agentic.scm.mirrors[" + REPO + "]", () -> ROOT.resolve("repo").toUri().toString());
	}

	/** Per-role scripted replies; the reviewer approves only if the edit landed. */
	@TestConfiguration(proxyBeanMethods = false)
	static class ScriptedModels {
		@Bean
		@Primary
		AgentModels scriptedAgentModels() {
			Map<AgentRole, Deque<ModelReply>> scripts = new EnumMap<>(AgentRole.class);
			scripts.put(AgentRole.TRIAGE, new ArrayDeque<>(List.of(answer("RISK: MEDIUM\nRATIONALE: user-visible text"))));
			scripts.put(AgentRole.PLANNER, new ArrayDeque<>(List.of(
					call("view_file", Map.of("path", "hello.txt")),
					answer("## Requirements\n1. WHEN the file is read THE SYSTEM SHALL greet the agent."))));
			scripts.put(AgentRole.CODER, new ArrayDeque<>(List.of(
					// test writer: may not touch code, writes a test that fails until the greeting changes
					call("edit_file", Map.of("path", "hello.txt", "old_string", "hello world", "new_string", "hello agent")),
					call("create_file", Map.of("path", "tests/greeting.sh", "content", "grep -q 'hello agent' hello.txt\n")),
					answer("tests/greeting.sh: the greeting names the agent."),
					// coder
					call("edit_file", Map.of("path", "hello.txt", "old_string", "hello world", "new_string", "hello agent")),
					call("run_command", Map.of("command", "grep -q hello hello.txt")),
					answer("Changed the greeting."))));
			scripts.put(AgentRole.REVIEWER, new ArrayDeque<>(List.of(
					// spec critic
					answer("No problems found.\nSPEC_VERDICT: OK"),
					call("show_diff", Map.of()),
					answer("Diff matches the spec.\nVERDICT: APPROVE"))));
			return role -> new AgentModel() {
				@Override
				public String id() {
					return "scripted/" + role;
				}

				@Override
				public Mono<ModelReply> complete(ModelRequest request) {
					ModelReply next = scripts.get(role).poll();
					return next == null ? Mono.error(new IllegalStateException("no more replies for " + role)) : Mono.just(next);
				}
			};
		}

		private static ModelReply call(String tool, Map<String, Object> args) {
			return new ModelReply("", List.of(new ToolCall(UUID.randomUUID().toString(), tool, args, args.toString())),
					new Usage(1000, 100, 0, 0, 6_000), "tool_use");
		}

		private static ModelReply answer(String text) {
			return new ModelReply(text, List.of(), new Usage(500, 50, 0, 0, 3_000), "end_turn");
		}
	}

	@Autowired
	TaskIntake intake;

	@Autowired
	RunQueries queries;

	@Autowired
	RunCommands commands;

	@Autowired
	SandboxJanitor janitor;

	@Autowired
	WorkspacePaths paths;

	private Run await(UUID id, Predicate<Run> condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 120_000;
		Run run = queries.get(id).block().run();
		while (!condition.test(run)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out in " + run.state() + "; events: "
						+ queries.events(id, 0, 500).collectList().block());
			}
			Thread.sleep(100);
			run = queries.get(id).block().run();
		}
		return run;
	}

	private static Predicate<Run> waitingAt(Gate gate) {
		return r -> r.pendingGate() == gate || r.state() == RunState.NEEDS_HUMAN || r.state().isTerminal();
	}

	@Test
	void mediumRunWithRealStagesReachesPublishGateWithSpecDiffAndReview() throws Exception {
		UUID id = intake.submit(new NewTask(TaskOrigin.PROMPT, null, "Greet the agent", "Change hello world to hello agent",
				new RepositoryRef(ScmKind.GITHUB, URI.create(REPO)), null, "alice", null)).block().view().run().id();

		Run atSpec = await(id, waitingAt(Gate.SPEC));
		assertThat(atSpec.pendingGate()).as("state %s", atSpec.state()).isEqualTo(Gate.SPEC);
		assertThat(atSpec.risk()).isEqualTo(RiskLevel.MEDIUM);
		commands.decide(id, Gate.SPEC, GateDecision.APPROVE, "go", "bob").block();

		Run atPublish = await(id, waitingAt(Gate.PUBLISH));
		assertThat(atPublish.pendingGate()).as("state %s", atPublish.state()).isEqualTo(Gate.PUBLISH);
		assertThat(Files.readString(paths.repo(id).resolve("hello.txt"))).isEqualTo("hello agent\n");
		assertThat(atPublish.usage().totalTokens()).isPositive();
		List<RunEvent> events = queries.events(id, 0, 1000).collectList().block();
		assertThat(events).filteredOn(e -> e.type() == RunEventType.TOOL_RESULT && Boolean.TRUE.equals(e.payload().get("error")))
				.anySatisfy(e -> assertThat(String.valueOf(e.payload().get("output"))).contains("hello.txt is not a test file"));
		assertThat(events).filteredOn(e -> e.type() == RunEventType.ARTIFACT_PRODUCED && "tests".equals(e.payload().get("kind")))
				.singleElement().satisfies(e -> {
					assertThat(e.payload()).containsEntry("failedFirst", true);
					assertThat(((Map<?, ?>) e.payload().get("files")).keySet()).map(String::valueOf).contains("tests/greeting.sh");
				});
		assertThat(atPublish.usage().costMicroUsd()).isPositive();

		List<RunEvent> log = queries.events(id, 0, 500).collectList().block();
		assertThat(log).filteredOn(e -> e.type() == RunEventType.ARTIFACT_PRODUCED)
				.extracting(e -> e.payload().get("kind")).containsSubsequence("spec", "diff", "diff", "review");
		assertThat(log).filteredOn(e -> e.type() == RunEventType.TOOL_CALLED)
				.extracting(e -> e.payload().get("tool")).contains("view_file", "edit_file", "run_command", "show_diff");
		assertThat(log).filteredOn(e -> e.type() == RunEventType.ARTIFACT_PRODUCED
				&& "diff".equals(e.payload().get("kind"))).last()
				.satisfies(e -> assertThat(String.valueOf(e.payload().get("content"))).contains("+hello agent"));

		commands.cancel(id, "test done", "alice").block();
		assertThat(janitor.sweep().collectList().block()).contains(id);
		assertThat(paths.runDir(id)).doesNotExist();
	}
}
