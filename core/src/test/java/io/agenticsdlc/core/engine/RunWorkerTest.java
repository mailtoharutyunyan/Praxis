package io.agenticsdlc.core.engine;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static io.agenticsdlc.core.support.Fixtures.LIMITS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RunWorkerTest {

	private static final Duration LEASE = Duration.ofSeconds(3);

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final List<String> abandoned = new ArrayList<>();
	private final List<Throwable> stageFailures = new ArrayList<>();
	private final WorkerListener listener = new WorkerListener() {
		@Override
		public void stageFailed(Run run, Throwable cause) {
			stageFailures.add(cause);
		}

		@Override
		public void stepAbandoned(UUID runId, RunState stage, Throwable cause) {
			abandoned.add(stage + ":" + cause.getClass().getSimpleName());
		}
	};

	private static StageHandler handler(RunState stage, Function<StageContext, Mono<StageOutcome>> body) {
		return new StageHandler() {
			@Override
			public RunState stage() {
				return stage;
			}

			@Override
			public Mono<StageOutcome> execute(StageContext context) {
				return body.apply(context);
			}
		};
	}

	private static List<StageHandler> happyPath(RiskLevel risk) {
		List<StageHandler> handlers = new ArrayList<>();
		handlers.add(handler(RunState.TRIAGING, ctx -> Mono.just(new StageOutcome.Triaged(risk, "stub", Usage.ZERO))));
		for (RunState stage : List.of(RunState.PREPARING_CONTEXT, RunState.SPECIFYING, RunState.IMPLEMENTING,
				RunState.VERIFYING, RunState.REVIEWING, RunState.PUBLISHING)) {
			handlers.add(handler(stage, ctx -> Mono.just(StageOutcome.Completed.free())));
		}
		return handlers;
	}

	private RunWorker worker(List<StageHandler> handlers) {
		return new RunWorker(store, handlers, LIMITS, CLOCK, "worker-1", LEASE, listener);
	}

	private UUID submit() {
		return new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES).submit(Fixtures.prompt("alice")).block().view().run().id();
	}

	/** Process until nothing is claimable (the run waits for a human or is finished). */
	private void drain(RunWorker worker) {
		for (int i = 0; i < 50; i++) {
			if (!worker.processNext().block()) {
				return;
			}
		}
		throw new AssertionError("worker did not settle");
	}

	@Test
	void lowRiskRunStopsOnlyAtPublishGateThenOpensPr() {
		UUID runId = submit();
		RunWorker worker = worker(happyPath(RiskLevel.LOW));

		drain(worker);
		Run atGate = store.run(runId);
		assertThat(atGate.state()).isEqualTo(RunState.AWAITING_APPROVAL);
		assertThat(atGate.pendingGate()).isEqualTo(Gate.PUBLISH);
		assertThat(store.leased(runId)).isFalse();

		Run approved = atGate.decide(Gate.PUBLISH, GateDecision.APPROVE, Fixtures.T0);
		store.update(atGate, approved, List.of()).block();
		drain(worker);

		assertThat(store.run(runId).state()).isEqualTo(RunState.PR_OPEN);
		List<RunEvent> log = store.allEvents(runId);
		assertThat(log).extracting(RunEvent::seq).containsExactlyElementsOf(
				java.util.stream.LongStream.rangeClosed(1, log.size()).boxed().toList());
		assertThat(log).extracting(RunEvent::type).contains(RunEventType.RUN_CREATED, RunEventType.TRIAGED,
				RunEventType.GATE_OPENED);
	}

	@Test
	void mediumRiskStopsAtSpecGate() {
		UUID runId = submit();
		drain(worker(happyPath(RiskLevel.MEDIUM)));
		assertThat(store.run(runId).pendingGate()).isEqualTo(Gate.SPEC);
	}

	@Test
	void handlersCanStreamProgressEvents() {
		UUID runId = submit();
		List<StageHandler> handlers = new ArrayList<>(happyPath(RiskLevel.LOW));
		handlers.removeIf(h -> h.stage() == RunState.IMPLEMENTING);
		handlers.add(handler(RunState.IMPLEMENTING, ctx -> ctx
				.emit(RunEventType.TOOL_CALLED, "agent:coder", Map.of("tool", "view_file", "path", "pom.xml"))
				.thenReturn(StageOutcome.Completed.free())));
		drain(worker(handlers));
		assertThat(store.allEvents(runId)).extracting(RunEvent::type).contains(RunEventType.TOOL_CALLED);
	}

	@Test
	void handlerErrorEscalatesToHuman() {
		UUID runId = submit();
		List<StageHandler> handlers = new ArrayList<>(happyPath(RiskLevel.LOW));
		handlers.removeIf(h -> h.stage() == RunState.PREPARING_CONTEXT);
		handlers.add(handler(RunState.PREPARING_CONTEXT, ctx -> Mono.error(new IllegalStateException("clone failed"))));

		drain(worker(handlers));

		Run run = store.run(runId);
		assertThat(run.state()).isEqualTo(RunState.NEEDS_HUMAN);
		assertThat(run.resumeState()).isEqualTo(RunState.PREPARING_CONTEXT);
		assertThat(stageFailures).hasSize(1);
		assertThat(store.allEvents(runId)).anySatisfy(e -> assertThat(String.valueOf(e.payload().get("reason")))
				.contains("clone failed"));
	}

	@Test
	void stageTimeoutEscalates() {
		UUID runId = submit();
		List<StageHandler> handlers = new ArrayList<>(happyPath(RiskLevel.LOW));
		handlers.removeIf(h -> h.stage() == RunState.PREPARING_CONTEXT);
		handlers.add(handler(RunState.PREPARING_CONTEXT, ctx -> Mono.never()));
		RunLimits quick = new RunLimits(3, 2, 1_000_000, 5_000_000, Duration.ofMillis(50));

		RunWorker worker = new RunWorker(store, handlers, quick, CLOCK, "worker-1", LEASE, listener);
		drain(worker);

		assertThat(store.run(runId).state()).isEqualTo(RunState.NEEDS_HUMAN);
	}

	@Test
	void missingHandlerEscalates() {
		UUID runId = submit();
		drain(worker(List.of()));
		assertThat(store.run(runId).state()).isEqualTo(RunState.NEEDS_HUMAN);
		assertThat(store.run(runId).resumeState()).isEqualTo(RunState.TRIAGING);
	}

	@Test
	void staleWorkerCannotOverwriteAfterLosingLease() {
		UUID runId = submit();
		RunWorker worker = worker(List.of());
		worker.processNext().block(); // RECEIVED -> TRIAGING

		AtomicInteger calls = new AtomicInteger();
		RunWorker stale = worker(List.of(handler(RunState.TRIAGING, ctx -> {
			calls.incrementAndGet();
			store.stealLease(runId, "worker-2");
			return Mono.just(new StageOutcome.Triaged(RiskLevel.LOW, "late", Usage.ZERO));
		})));
		stale.processNext().block();

		assertThat(calls).hasValue(1);
		assertThat(store.run(runId).state()).isEqualTo(RunState.TRIAGING);
		assertThat(store.run(runId).risk()).isNull();
		assertThat(abandoned).containsExactly("TRIAGING:ConcurrentRunUpdateException");
	}

	@Test
	void nothingToDoReturnsFalse() {
		assertThat(worker(List.of()).processNext().block()).isFalse();
	}

	@Test
	void rejectsInvalidHandlerSetup() {
		List<StageHandler> duplicate = List.of(handler(RunState.TRIAGING, ctx -> Mono.empty()),
				handler(RunState.TRIAGING, ctx -> Mono.empty()));
		assertThatThrownBy(() -> worker(duplicate)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> worker(List.of(handler(RunState.AWAITING_APPROVAL, ctx -> Mono.empty()))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunWorker(store, List.of(), LIMITS, CLOCK, "w", Duration.ofSeconds(1), listener))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
