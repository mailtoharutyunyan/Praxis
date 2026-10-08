package io.agenticsdlc.core.engine;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.port.LeaseLostException;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Advances runs one stage at a time: claim a run (lease), run its stage handler while renewing the lease,
 * store the outcome as decided by {@link Transitions}, release the lease.
 * <p>
 * Failure handling: a handler error or timeout escalates the run to a human rather than retrying blindly.
 * Losing the lease or a concurrent change (cancel, another worker) abandons the step without writing; the
 * store's version fencing guarantees a stale worker cannot overwrite newer state.
 */
public final class RunWorker {

	private final RunStore store;
	private final Map<RunState, StageHandler> handlers;
	private final RunLimits limits;
	private final Clock clock;
	private final String owner;
	private final Duration lease;
	private final WorkerListener listener;

	public RunWorker(RunStore store, List<StageHandler> handlers, RunLimits limits, Clock clock, String owner,
			Duration lease, WorkerListener listener) {
		this.store = Objects.requireNonNull(store, "store");
		this.limits = Objects.requireNonNull(limits, "limits");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.owner = Objects.requireNonNull(owner, "owner");
		this.lease = Objects.requireNonNull(lease, "lease");
		this.listener = Objects.requireNonNull(listener, "listener");
		if (lease.compareTo(Duration.ofSeconds(3)) < 0) {
			throw new IllegalArgumentException("lease must be at least 3s so it can be renewed in time");
		}
		this.handlers = new EnumMap<>(RunState.class);
		for (StageHandler handler : handlers) {
			if (!handler.stage().isWorking() || handler.stage() == RunState.RECEIVED) {
				throw new IllegalArgumentException("no handler can be registered for " + handler.stage());
			}
			if (this.handlers.putIfAbsent(handler.stage(), handler) != null) {
				throw new IllegalArgumentException("two handlers registered for " + handler.stage());
			}
		}
	}

	public String owner() {
		return owner;
	}

	/** Claim and advance one run. Emits true if a run was processed, false if none was available. */
	public Mono<Boolean> processNext() {
		return store.claim(owner, lease)
				.flatMap(run -> process(run).thenReturn(true))
				.defaultIfEmpty(false);
	}

	private Mono<Void> process(Run claimed) {
		long started = clock.millis();
		Mono<Boolean> leaseLost = Flux.interval(lease.dividedBy(3))
				.concatMap(tick -> store.renewLease(claimed.id(), owner, lease))
				.filter(held -> !held)
				.next();

		Mono<Void> work = store.find(claimed.id())
				.map(view -> new RunView(claimed, view.task()))
				.flatMap(this::step)
				.doOnNext(stored -> listener.stepCompleted(claimed, stored,
						Duration.ofMillis(clock.millis() - started)))
				.then();

		return work
				.takeUntilOther(leaseLost.doOnNext(lost -> listener.stepAbandoned(claimed.id(), claimed.state(),
						new LeaseLostException(claimed.id(), owner))))
				// ConcurrentRunUpdateException / LeaseLostException are expected races; anything else (e.g. the
				// database is unreachable) also leaves the run untouched, and its lease expires for another worker.
				.onErrorResume(e -> {
					listener.stepAbandoned(claimed.id(), claimed.state(), e);
					return Mono.empty();
				})
				.then(Mono.defer(() -> store.releaseLease(claimed.id(), owner)))
				.onErrorResume(e -> Mono.empty());
	}

	private Mono<Run> step(RunView view) {
		Run run = view.run();
		if (run.state() == RunState.RECEIVED) {
			Transitions.Step step = Transitions.start(run, clock.instant());
			return store.update(run, step.next(), step.events());
		}
		return outcome(view)
				.map(outcome -> Transitions.apply(run, view.task(), outcome, limits, clock.instant()))
				.flatMap(step -> store.update(run, step.next(), step.events()));
	}

	private Mono<StageOutcome> outcome(RunView view) {
		Run run = view.run();
		StageHandler handler = handlers.get(run.state());
		if (handler == null) {
			return Mono.just(new StageOutcome.Escalate("no handler is configured for stage " + run.state(), Usage.ZERO));
		}
		return Mono.defer(() -> handler.execute(new StageContext(view, store, owner, clock)))
				.switchIfEmpty(Mono.error(() -> new IllegalStateException(
						"handler for " + run.state() + " completed without an outcome")))
				.timeout(limits.stageTimeout())
				.onErrorResume(e -> !(e instanceof LeaseLostException), e -> {
					listener.stageFailed(run, e);
					String reason = e instanceof TimeoutException
							? "stage " + run.state() + " did not finish within " + limits.stageTimeout()
							: "stage " + run.state() + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
					return Mono.just(new StageOutcome.Escalate(reason, Usage.ZERO));
				});
	}
}
