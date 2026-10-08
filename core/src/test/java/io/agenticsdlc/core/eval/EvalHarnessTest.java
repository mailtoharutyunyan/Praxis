package io.agenticsdlc.core.eval;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.RunWorker;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.engine.WorkerListener;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import io.agenticsdlc.core.workspace.CommandResult;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class EvalHarnessTest {

	private final Clock clock = Clock.systemUTC();
	private final InMemoryRunStore store = new InMemoryRunStore(clock);
	/** One file map per run, as in production where each run has its own container. */
	private final Map<UUID, Map<String, String>> files = new java.util.concurrent.ConcurrentHashMap<>();
	private final List<String> commandsRun = java.util.Collections.synchronizedList(new ArrayList<>());
	private final io.agenticsdlc.core.workspace.Sandbox sandbox = new io.agenticsdlc.core.workspace.Sandbox() {
		@Override
		public Mono<Void> start(UUID runId, io.agenticsdlc.core.workspace.SandboxSpec spec) {
			return Mono.empty();
		}

		@Override
		public Mono<CommandResult> exec(UUID runId, String command, Duration timeout) {
			commandsRun.add(command);
			Map<String, String> own = filesOf(runId);
			boolean ok = switch (command) {
				case "check-fix" -> own.containsKey("src/fixed.txt") && own.containsKey("test/Hidden.java");
				case "check-old" -> true;
				default -> false;
			};
			return Mono.just(new CommandResult(command, ok ? 0 : 1, "", false, false, Duration.ZERO));
		}

		@Override
		public Mono<String> readFile(UUID runId, String path, int maxBytes) {
			return Mono.justOrEmpty(filesOf(runId).get(path));
		}

		@Override
		public Mono<Void> writeFile(UUID runId, String path, String content) {
			filesOf(runId).put(path, content);
			return Mono.empty();
		}

		@Override
		public Mono<Void> destroy(UUID runId) {
			return Mono.empty();
		}
	};

	private Map<String, String> filesOf(UUID runId) {
		return files.computeIfAbsent(runId, id -> new java.util.concurrent.ConcurrentHashMap<>());
	}
	private Disposable workerLoop;
	private EvalHarness harness;

	private static StageHandler stage(RunState state, java.util.function.Function<StageContext, StageOutcome> body) {
		return new StageHandler() {
			@Override
			public RunState stage() {
				return state;
			}

			@Override
			public Mono<StageOutcome> execute(StageContext context) {
				return Mono.fromSupplier(() -> body.apply(context));
			}
		};
	}

	@BeforeEach
	void setUp() {
		// The "agent" only fixes tasks whose title says so; the medium risk forces an auto-approved SPEC gate.
		List<StageHandler> handlers = new ArrayList<>();
		handlers.add(stage(RunState.TRIAGING, c -> new StageOutcome.Triaged(RiskLevel.MEDIUM, "x", Usage.ZERO)));
		handlers.add(stage(RunState.IMPLEMENTING, c -> {
			if (c.task().title().contains("fixable")) {
				filesOf(c.run().id()).put("src/fixed.txt", "yes");
			}
			return new StageOutcome.Completed(new Usage(1_000, 100, 0, 0, 2_000), Map.of());
		}));
		for (RunState state : List.of(RunState.PREPARING_CONTEXT, RunState.SPECIFYING, RunState.VERIFYING,
				RunState.REVIEWING)) {
			handlers.add(stage(state, c -> StageOutcome.Completed.free()));
		}
		RunWorker worker = new RunWorker(store, handlers, Fixtures.LIMITS, clock, "w", Duration.ofSeconds(10),
				WorkerListener.NONE);
		workerLoop = Flux.interval(Duration.ofMillis(5)).concatMap(t -> worker.processNext()).subscribe();
		RunCommands commands = new RunCommands(store, clock, true);
		harness = new EvalHarness(new TaskIntake(store, clock, UUID::randomUUID, Fixtures.REPOSITORIES),
				new RunQueries(store, store, Duration.ofMillis(20)), commands, sandbox, clock, Duration.ofMillis(10),
				Duration.ofSeconds(10), Duration.ofSeconds(5));
	}

	@AfterEach
	void tearDown() {
		workerLoop.dispose();
	}

	private static EvalCase evalCase(String id, String title) {
		return new EvalCase(id, title, "fix it", Fixtures.REPO, "main", Map.of("test/Hidden.java", "class Hidden {}"),
				List.of("check-fix"), List.of("check-old"));
	}

	@Test
	void gradesTrialsWithHiddenTestsAndAggregates() {
		EvalReport report = harness.run("sample", List.of(evalCase("A", "fixable bug"), evalCase("B", "hard bug")), 2, 2)
				.block(Duration.ofSeconds(30));

		assertThat(report.trials()).hasSize(4);
		assertThat(report.byCase().get("A")).allSatisfy(t -> {
			assertThat(t.passed()).isTrue();
			assertThat(t.reachedPublish()).isTrue();
			assertThat(t.usage().costMicroUsd()).isEqualTo(2_000);
		});
		assertThat(report.byCase().get("B")).allSatisfy(t -> {
			assertThat(t.passed()).isFalse();
			assertThat(t.failedChecks()).containsExactly("`check-fix` exit 1");
		});
		assertThat(report.passAt1()).isEqualTo(0.5);
		assertThat(report.passHatK()).isEqualTo(0.5);
		assertThat(report.meanCostUsd()).isEqualTo(0.002);
		assertThat(report.toMarkdown()).contains("| 50% | 50% | 2 | 4 |", "| A | 1 | PASS |", "| B | 2 | FAIL |");
		assertThat(report.trials()).allSatisfy(t -> assertThat(store.run(t.runId()).state()).isEqualTo(RunState.CANCELLED));
	}

	@Test
	void hiddenFilesAreOnlyAddedForGrading() {
		harness.run("s", List.of(evalCase("A", "fixable bug")), 1, 1).block(Duration.ofSeconds(30));
		assertThat(commandsRun).containsExactly("check-fix", "check-old");
		assertThat(files.values()).singleElement().satisfies(f -> assertThat(f).containsKeys("test/Hidden.java",
				"src/fixed.txt"));
	}

	@Test
	void casesNeedGradingCommands() {
		assertThatThrownBy(() -> new EvalCase("x", "t", "d", Fixtures.REPO, null, Map.of(), List.of(), List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(new EvalReport("empty", 1, List.of()).passAt1()).isZero();
	}
}
