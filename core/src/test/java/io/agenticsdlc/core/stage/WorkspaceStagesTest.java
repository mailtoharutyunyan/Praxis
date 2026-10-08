package io.agenticsdlc.core.stage;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.ProjectConfig;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SandboxSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class WorkspaceStagesTest {

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final FakeSandbox sandbox = new FakeSandbox();
	private Set<String> rootFiles = Set.of("pom.xml", "mvnw");
	private final RepositoryCheckout checkout = new RepositoryCheckout() {
		@Override
		public Mono<CheckoutInfo> checkout(RunView view) {
			return Mono.just(new CheckoutInfo("main", "abc123", "agent/" + view.run().id(), rootFiles,
					new ProjectConfig(null, "./mvnw -q dependency:go-offline", null, null), "# Agents"));
		}

		@Override
		public Mono<String> diff(UUID runId) {
			return Mono.just("");
		}

		@Override
		public Mono<Void> remove(UUID runId) {
			return Mono.empty();
		}
	};
	private final RunWorkspace workspace = new RunWorkspace(checkout, sandbox, Duration.ofMinutes(5));
	private StageContext context;

	@BeforeEach
	void setUp() {
		RunView view = new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES)
				.submit(Fixtures.prompt("alice")).block().view();
		store.claim("w", Duration.ofSeconds(30)).block();
		context = new StageContext(view, store, "w", CLOCK);
	}

	@Test
	void prepareRunsSetupAndBaselineBuild() {
		StageOutcome outcome = new PrepareContextStage(workspace).execute(context).block();

		assertThat(outcome).isInstanceOfSatisfying(StageOutcome.Completed.class, c -> assertThat(c.summary())
				.containsEntry("tool", "maven").containsEntry("baselineBuild", "PASSED")
				.containsEntry("agentInstructions", true));
		assertThat(sandbox.started).containsExactly("maven:3.9-eclipse-temurin-25");
		assertThat(sandbox.commands).containsExactly("./mvnw -q dependency:go-offline",
				"./mvnw -B -ntp -DskipTests test-compile");
		assertThat(store.allEvents(context.run().id())).filteredOn(e -> e.type() == RunEventType.COMMAND_OUTPUT)
				.hasSize(2);
	}

	@Test
	void failingBaselineIsRecordedNotFatalAndStopsAtFirstFailure() {
		sandbox.exitCodes.put("./mvnw -q dependency:go-offline", 1);
		StageOutcome outcome = new PrepareContextStage(workspace).execute(context).block();
		assertThat(outcome).isInstanceOfSatisfying(StageOutcome.Completed.class,
				c -> assertThat(c.summary()).containsEntry("baselineBuild", "FAILED"));
		assertThat(sandbox.commands).hasSize(1);
	}

	@Test
	void unknownToolchainEscalates() {
		rootFiles = Set.of("README.md");
		assertThat(new PrepareContextStage(workspace).execute(context).block())
				.isInstanceOf(StageOutcome.Escalate.class);
		assertThat(new VerifyStage(workspace).execute(context).block()).isInstanceOf(StageOutcome.Escalate.class);
	}

	@Test
	void verifyPassesOrAsksForReworkWithOutput() {
		assertThat(new VerifyStage(workspace).execute(context).block()).isInstanceOf(StageOutcome.Completed.class);

		sandbox.exitCodes.put("./mvnw -B -ntp verify", 1);
		StageOutcome failed = new VerifyStage(workspace).execute(context).block();
		assertThat(failed).isInstanceOfSatisfying(StageOutcome.NeedsRework.class, r -> assertThat(r.reason())
				.contains("./mvnw -B -ntp verify").contains("exited with 1").contains("output of ./mvnw -B -ntp verify"));

		sandbox.timeouts.add("./mvnw -B -ntp verify");
		assertThat(new VerifyStage(workspace).execute(context).block())
				.isInstanceOfSatisfying(StageOutcome.NeedsRework.class, r -> assertThat(r.reason()).contains("timed out"));
	}

	@Test
	void eventsCarryCommandResults() {
		new VerifyStage(workspace).execute(context).block();
		List<RunEvent> log = store.allEvents(context.run().id());
		assertThat(log.getLast().type()).isEqualTo(RunEventType.ARTIFACT_PRODUCED);
		assertThat(log.getLast().payload()).containsEntry("kind", RunHistory.DIFF);
		RunEvent last = log.get(log.size() - 2);
		assertThat(last.actor()).isEqualTo(RunWorkspace.ACTOR);
		assertThat(last.payload()).containsEntry("command", "./mvnw -B -ntp verify").containsEntry("exitCode", 0);
	}

	private static final class FakeSandbox implements Sandbox {
		final List<String> started = new ArrayList<>();
		final List<String> commands = new ArrayList<>();
		final Map<String, Integer> exitCodes = new HashMap<>();
		final Set<String> timeouts = new java.util.HashSet<>();

		@Override
		public Mono<Void> start(UUID runId, SandboxSpec spec) {
			started.add(spec.image());
			return Mono.empty();
		}

		@Override
		public Mono<CommandResult> exec(UUID runId, String command, Duration timeout) {
			commands.add(command);
			return Mono.just(new CommandResult(command, exitCodes.getOrDefault(command, 0), "output of " + command,
					false, timeouts.contains(command), Duration.ofMillis(10)));
		}

		@Override
		public Mono<String> readFile(UUID runId, String relativePath, int maxBytes) {
			return Mono.error(new java.nio.file.NoSuchFileException(relativePath));
		}

		@Override
		public Mono<Void> writeFile(UUID runId, String relativePath, String content) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> destroy(UUID runId) {
			return Mono.empty();
		}
	}
}
