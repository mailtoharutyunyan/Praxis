package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.support.TestRepos;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR-0008 end to end: every agent stage runs on "Claude Code" in the Docker sandbox. The CLI is a fake (a shell
 * script printing a stream-json transcript and editing a file), mounted read-only from a host directory as the real
 * one would be; the checkout, sandbox, verification and gates are real. An untrusted task never reaches the CLI.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "agentic.sandbox.enabled=true", "agentic.agent.enabled=true",
		"agentic.stub-stages.enabled=false", "agentic.sandbox.network=bridge", "agentic.sandbox.memory=256MB",
		"agentic.worker.poll-interval=50ms", "agentic.agent.tests-first=false", "agentic.agent.spec-critic=false",
		"agentic.memory.enabled=false", "agentic.scan.secrets=false", "agentic.scan.dependencies=false",
		"agentic.agent.cli.enabled=true", "agentic.agent.cli.token=" + ClaudeCodePipelineTest.TOKEN,
		"agentic.agent.cli.model=claude-sonnet-5-5",
		// No API engine: untrusted tasks then have nowhere to run.
		"agentic.models.providers.anthropic.api-key=" })
class ClaudeCodePipelineTest {

	static final String TOKEN = "sk-ant-oat01-fake-token-for-tests";
	private static final Path ROOT;
	private static final Path TOOLS;
	private static final String REPO = "https://github.com/acme/cli-demo.git";

	static {
		try {
			ROOT = Files.createTempDirectory("agentic-cli-pipeline");
			TestRepos.demoRepo(ROOT.resolve("repo"));
			TOOLS = Files.createDirectories(ROOT.resolve("tools"));
			try (InputStream fake = ClaudeCodePipelineTest.class.getResourceAsStream("/claude-code/fake-claude.sh")) {
				Files.write(TOOLS.resolve("claude"), fake.readAllBytes());
			}
			Files.setPosixFilePermissions(TOOLS.resolve("claude"), PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.sandbox.workspace-root", () -> ROOT.resolve("workspaces").toString());
		registry.add("agentic.scm.mirrors[" + REPO + "]", () -> ROOT.resolve("repo").toUri().toString());
		registry.add("agentic.agent.cli.tools-dir", TOOLS::toString);
	}

	@Autowired
	TaskIntake intake;

	@Autowired
	RunQueries queries;

	@Autowired
	RunCommands commands;

	@Autowired
	Sandbox sandbox;

	private UUID submit(TaskOrigin origin) {
		return intake.submit(new NewTask(origin, origin == TaskOrigin.PROMPT ? null : "SHOP-7", "Greet agents",
				"Say hello to agents in hello.txt", new RepositoryRef(ScmKind.GITHUB, URI.create(REPO)), null, "alice",
				null)).block().view().run().id();
	}

	private Run await(UUID id, Predicate<Run> condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 180_000;
		Run run = queries.get(id).block().run();
		while (!condition.test(run)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out in " + run.state() + "; events: " + events(id));
			}
			Thread.sleep(100);
			run = queries.get(id).block().run();
		}
		return run;
	}

	private List<RunEvent> events(UUID id) {
		return queries.events(id, 0, 1000).collectList().block();
	}

	private String inSandbox(UUID id, String command) {
		return sandbox.exec(id, command, Duration.ofSeconds(30)).block().output();
	}

	@Test
	void everyAgentStageRunsOnTheCliInTheSandbox() throws Exception {
		UUID id = submit(TaskOrigin.PROMPT);
		try {
			Run atPublish = await(id, r -> r.pendingGate() == Gate.PUBLISH || r.state() == RunState.NEEDS_HUMAN
					|| r.state().isTerminal());
			List<RunEvent> events = events(id);
			assertThat(atPublish.pendingGate()).as("events: %s", events).isEqualTo(Gate.PUBLISH);

			assertThat(events).filteredOn(e -> e.type() == RunEventType.AGENT_MESSAGE).extracting(RunEvent::actor)
					.containsExactly("agent:triage", "agent:planner", "agent:coder", "agent:reviewer");
			assertThat(events).filteredOn(e -> e.type() == RunEventType.AGENT_MESSAGE)
					.allSatisfy(e -> assertThat(e.payload()).containsEntry("model", "claude-code/claude-sonnet-5-5"));
			assertThat(events).filteredOn(e -> e.type() == RunEventType.TOOL_CALLED && e.actor().equals("agent:coder"))
					.extracting(e -> e.payload().get("tool")).containsExactly("Read", "Edit");
			assertThat(events).filteredOn(e -> e.type() == RunEventType.TOOL_RESULT && e.actor().equals("agent:coder"))
					.extracting(e -> e.payload().get("output")).containsExactly("hello world",
							"edited with token [REDACTED]");
			assertThat(events).allSatisfy(e -> assertThat(e.payload().toString()).doesNotContain(TOKEN));
			assertThat(events).filteredOn(e -> e.type() == RunEventType.ARTIFACT_PRODUCED
					&& "diff".equals(e.payload().get("kind"))).isNotEmpty()
					.allSatisfy(e -> assertThat(String.valueOf(e.payload().get("content"))).contains("+hello agents"));
			// Each call's cost as Claude Code reported it: triage, planner, reviewer 0.01 and coder 0.05 USD.
			assertThat(atPublish.usage().costMicroUsd()).isEqualTo(80_000);

			String log = inSandbox(id, "cat /tmp/fake-claude.log");
			assertThat(log).doesNotContain(TOKEN)
					.contains("env: token=set telemetry=1 nonessential=1 scrub=1 config=/tmp/.agentic-cli/config",
							"--model claude-sonnet-5-5", "--permission-mode dontAsk", "system: has the engine note");
			assertThat(log.lines().filter(line -> line.startsWith("args: "))).hasSize(4);
			assertThat(inSandbox(id, "env")).as("the token is not in the container's environment").doesNotContain(TOKEN);
			assertThat(inSandbox(id, "find /tmp/.agentic-cli -name '*.md' | wc -l").strip())
					.as("scratch files are removed").isEqualTo("0");
			commands.decide(id, Gate.PUBLISH, GateDecision.REJECT, "test over", "bob").block();
		}
		finally {
			sandbox.destroy(id).block();
		}
	}

	@Test
	void anUntrustedTaskNeverRunsOnTheCli() throws Exception {
		UUID id = submit(TaskOrigin.JIRA);
		try {
			Run run = await(id, r -> r.state() == RunState.NEEDS_HUMAN || r.state().isTerminal());
			List<RunEvent> events = events(id);
			assertThat(run.state()).as("events: %s", events).isEqualTo(RunState.NEEDS_HUMAN);
			assertThat(events).filteredOn(e -> e.type() == RunEventType.ERROR).last()
					.satisfies(e -> assertThat(String.valueOf(e.payload().get("reason")))
							.contains("JIRA", "untrusted", "never run on Claude Code"));
			assertThat(events).noneMatch(e -> e.type() == RunEventType.AGENT_MESSAGE
					|| e.type() == RunEventType.TOOL_CALLED || e.type() == RunEventType.COMMAND_OUTPUT);
			assertThat(run.usage().totalTokens()).isZero();
		}
		finally {
			sandbox.destroy(id).block();
		}
	}
}
