package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.adapter.in.eval.EvalCommand;
import io.agenticsdlc.core.agent.AgentModel;
import io.agenticsdlc.core.agent.AgentModels;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.ModelReply;
import io.agenticsdlc.core.agent.ModelRequest;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.eval.EvalReport;
import io.agenticsdlc.support.TestRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

/** M7: a suite file is replayed through the real pipeline and graded with hidden checks in the sandbox. */
@Import({ TestcontainersConfiguration.class, EvalCommandTest.Models.class })
@SpringBootTest(properties = { "agentic.sandbox.enabled=true", "agentic.agent.enabled=true", "agentic.agent.tests-first=false", "agentic.agent.spec-critic=false",
		"agentic.stub-stages.enabled=false", "agentic.sandbox.network=none", "agentic.sandbox.memory=256MB",
		"agentic.worker.poll-interval=50ms", "agentic.worker.concurrency=4" })
class EvalCommandTest {

	private static final Path ROOT;

	static {
		try {
			ROOT = Files.createTempDirectory("agentic-eval");
			TestRepos.demoRepo(ROOT.resolve("repo"));
			Files.createDirectories(ROOT.resolve("suite/hidden"));
			Files.writeString(ROOT.resolve("suite/hidden/check.sh"), "grep -q 'hello agent' hello.txt\n");
			Files.writeString(ROOT.resolve("suite/suite.yaml"), """
					name: demo
					trials: 2
					concurrency: 2
					cases:
					  - id: GREET-1
					    title: Greet the agent
					    description: Change the greeting in hello.txt to "hello agent".
					    repository: { kind: GITHUB, clone-url: https://github.com/acme/eval-demo.git }
					    ref: main
					    hidden-files-dir: hidden
					    fail-to-pass: ["sh check.sh"]
					    pass-to-pass: ["test -f hello.txt"]
					  - id: GREET-2
					    title: Say goodbye
					    description: The greeting should say goodbye.
					    repository: { kind: GITHUB, clone-url: https://github.com/acme/eval-demo.git }
					    hidden-files: { check2.sh: "grep -q goodbye hello.txt" }
					    fail-to-pass: ["sh check2.sh"]
					""");
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.sandbox.workspace-root", () -> ROOT.resolve("workspaces").toString());
		registry.add("agentic.scm.mirrors[https://github.com/acme/eval-demo.git]", () -> ROOT.resolve("repo").toUri()
				.toString());
	}

	/** Stateless per-role behaviour: the coder always makes the same edit, so GREET-1 passes and GREET-2 fails. */
	@TestConfiguration(proxyBeanMethods = false)
	static class Models {
		@Bean
		@Primary
		AgentModels evalModels() {
			return role -> new AgentModel() {
				@Override
				public String id() {
					return "scripted/" + role;
				}

				@Override
				public Mono<ModelReply> complete(ModelRequest request) {
					Usage usage = new Usage(800, 80, 0, 0, 4_000);
					if (role == AgentRole.CODER && request.messages().size() == 1) {
						Map<String, Object> args = Map.of("path", "hello.txt", "old_string", "hello world", "new_string",
								"hello agent");
						return Mono.just(new ModelReply("", List.of(new ToolCall("c1", "edit_file", args, args.toString())),
								usage, "tool_use"));
					}
					String text = switch (role) {
						case TRIAGE -> "RISK: MEDIUM\nRATIONALE: demo";
						case PLANNER -> "## Requirements\n1. WHEN read THE SYSTEM SHALL greet.";
						case CODER -> "Edited the greeting.";
						case REVIEWER -> "OK\nVERDICT: APPROVE";
					};
					return Mono.just(new ModelReply(text, List.of(), usage, "end_turn"));
				}
			};
		}
	}

	@Autowired
	EvalCommand command;

	@Test
	void replaysSuiteAndGradesWithHiddenChecks() throws Exception {
		Path out = ROOT.resolve("report");
		EvalReport report = command.execute(ROOT.resolve("suite/suite.yaml"), out);

		assertThat(report.trials()).hasSize(4);
		assertThat(report.byCase().get("GREET-1")).allSatisfy(t -> assertThat(t.passed()).as("%s", t).isTrue());
		assertThat(report.byCase().get("GREET-2")).allSatisfy(t -> {
			assertThat(t.passed()).isFalse();
			assertThat(t.reachedPublish()).isTrue();
			assertThat(t.failedChecks()).containsExactly("`sh check2.sh` exit 1");
		});
		assertThat(report.passAt1()).isEqualTo(0.5);
		assertThat(report.passHatK()).isEqualTo(0.5);
		assertThat(report.meanCostUsd()).isPositive();

		assertThat(Files.readString(out.resolve("report.md"))).contains("# Evaluation: demo", "| GREET-1 | 1 | PASS |");
		assertThat(Files.readString(out.resolve("report.json"))).contains("\"passAt1\" : 0.5", "\"caseId\" : \"GREET-2\"");
	}
}
