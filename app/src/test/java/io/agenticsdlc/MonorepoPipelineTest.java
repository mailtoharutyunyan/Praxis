package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.workspace.Sandbox;
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
 * ADR-0006 end to end: a monorepo with two services and a Postgres sidecar. The baseline builds both services; the
 * agent changes one, so only that one is verified, and its tests reach the database by host name.
 */
@Import({ TestcontainersConfiguration.class, MonorepoPipelineTest.ScriptedModels.class })
@SpringBootTest(properties = { "agentic.sandbox.enabled=true", "agentic.agent.enabled=true",
		"agentic.stub-stages.enabled=false", "agentic.sandbox.network=none", "agentic.sandbox.memory=256MB",
		"agentic.worker.poll-interval=50ms", "agentic.agent.tests-first=false", "agentic.agent.spec-critic=false",
		"agentic.memory.enabled=false", "agentic.scan.secrets=false" })
class MonorepoPipelineTest {

	private static final Path ROOT;
	private static final String REPO = "https://github.com/acme/monorepo.git";

	static {
		try {
			ROOT = Files.createTempDirectory("agentic-monorepo");
			TestRepos.createRepo(ROOT.resolve("repo"), Map.of(
					".agentic-sdlc.yml", """
							services:
							  - { name: orders, path: services/orders, image: %1$s, build: test -f orders.txt, test: grep -q order orders.txt }
							  - { name: web, path: services/web, image: %1$s, build: test -f page.txt,
							      test: "grep -q welcome page.txt && nc -z -w 5 $DB_HOST 5432" }
							sidecars:
							  - { name: db, image: postgres:16-alpine, env: { POSTGRES_PASSWORD: test }, ready: pg_isready -U postgres }
							env:
							  DB_HOST: db
							""".formatted(TestRepos.ALPINE),
					"services/orders/orders.txt", "order\n",
					"services/web/page.txt", "hello\n"));
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

	@TestConfiguration(proxyBeanMethods = false)
	static class ScriptedModels {
		@Bean
		@Primary
		AgentModels monorepoScriptedModels() {
			Map<AgentRole, Deque<ModelReply>> scripts = new EnumMap<>(AgentRole.class);
			scripts.put(AgentRole.TRIAGE, new ArrayDeque<>(List.of(answer("RISK: LOW\nRATIONALE: copy change"))));
			scripts.put(AgentRole.PLANNER, new ArrayDeque<>(List.of(answer("## Requirements\n1. WHEN opened THE PAGE SHALL welcome."))));
			scripts.put(AgentRole.CODER, new ArrayDeque<>(List.of(
					call("run_command", Map.of("command", "pwd && nc -z -w 5 \"$DB_HOST\" 5432 && echo db-up", "service", "web")),
					call("edit_file", Map.of("path", "services/web/page.txt", "old_string", "hello", "new_string", "welcome")),
					answer("Welcomed."))));
			scripts.put(AgentRole.REVIEWER, new ArrayDeque<>(List.of(answer("Fine.\nVERDICT: APPROVE"))));
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
					Usage.ZERO, "tool_use");
		}

		private static ModelReply answer(String text) {
			return new ModelReply(text, List.of(), Usage.ZERO, "end_turn");
		}
	}

	@Autowired
	TaskIntake intake;

	@Autowired
	RunQueries queries;

	@Autowired
	RunCommands commands;

	@Autowired
	Sandbox sandbox;

	private Run await(UUID id, Predicate<Run> condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 180_000;
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

	@Test
	void onlyTheChangedServiceIsVerifiedAndItsTestsReachTheSidecar() throws Exception {
		UUID id = intake.submit(new NewTask(TaskOrigin.PROMPT, null, "Welcome visitors", "Say welcome on the page",
				new RepositoryRef(ScmKind.GITHUB, URI.create(REPO)), null, "alice", null)).block().view().run().id();
		try {
			Run atPublish = await(id, r -> r.pendingGate() == Gate.PUBLISH || r.state() == RunState.NEEDS_HUMAN
					|| r.state().isTerminal());
			List<RunEvent> events = queries.events(id, 0, 1000).collectList().block();
			assertThat(atPublish.pendingGate()).as("events: %s", events).isEqualTo(Gate.PUBLISH);

			List<RunEvent> commandsRun = events.stream().filter(e -> e.type() == RunEventType.COMMAND_OUTPUT).toList();
			long baselineEnd = events.stream().filter(e -> e.type() == RunEventType.STAGE_COMPLETED
					&& "PREPARING_CONTEXT".equals(e.payload().get("stage"))).findFirst().orElseThrow().seq();
			assertThat(commandsRun).filteredOn(e -> e.seq() < baselineEnd).extracting(e -> e.payload().get("service"))
					.as("the baseline builds every service").contains("orders", "web");
			assertThat(commandsRun).filteredOn(e -> e.seq() > baselineEnd).extracting(e -> e.payload().get("service"))
					.as("verification runs only the changed service").containsOnly("web");
			assertThat(events).filteredOn(e -> e.type() == RunEventType.TOOL_RESULT)
					.anySatisfy(e -> assertThat(String.valueOf(e.payload().get("output"))).contains("/workspace/services/web", "db-up"));
			assertThat(events).filteredOn(e -> e.type() == RunEventType.STAGE_COMPLETED
					&& "PREPARING_CONTEXT".equals(e.payload().get("stage")))
					.singleElement().satisfies(e -> assertThat(String.valueOf(e.payload().get("detail")))
							.contains("orders (services/orders", "web (services/web", "sidecars=[db]"));
			commands.decide(id, Gate.PUBLISH, GateDecision.REJECT, "test over", "bob").block();
		}
		finally {
			sandbox.destroy(id).block();
		}
	}
}
