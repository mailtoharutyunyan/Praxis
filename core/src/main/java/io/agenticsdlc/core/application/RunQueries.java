package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.port.RunChangeSignals;
import io.agenticsdlc.core.port.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read side: run details, listings, the event log, and a live follow of a run's events. */
public final class RunQueries {

	public static final int MAX_PAGE = 500;

	private final RunStore store;
	private final RunChangeSignals signals;
	private final Duration fallbackPoll;

	/**
	 * @param fallbackPoll how often a follower re-reads the store even without a signal, so a lost
	 *        notification delays an event by at most this long
	 */
	public RunQueries(RunStore store, RunChangeSignals signals, Duration fallbackPoll) {
		this.store = Objects.requireNonNull(store, "store");
		this.signals = Objects.requireNonNull(signals, "signals");
		this.fallbackPoll = Objects.requireNonNull(fallbackPoll, "fallbackPoll");
	}

	public Mono<RunView> get(UUID runId) {
		return store.find(runId).switchIfEmpty(Mono.error(() -> new RunNotFoundException(runId)));
	}

	public Flux<RunView> list(Set<RunState> states, RunStore.Cursor before, int limit) {
		return store.list(states == null ? Set.of() : states, before, clamp(limit));
	}

	public Flux<RunEvent> events(UUID runId, long afterSeq, int limit) {
		return get(runId).thenMany(store.events(runId, afterSeq, clamp(limit)));
	}

	/** Where the run stands now, from its state and recent events. */
	public Mono<RunProgress> progress(RunView view) {
		return store.latestEvents(view.run().id(), Set.of(RunEventType.STATE_CHANGED, RunEventType.AGENT_MESSAGE,
				RunEventType.TOOL_CALLED, RunEventType.COMMAND_OUTPUT), 300).collectList()
				.map(events -> RunProgress.of(view.run(), events));
	}

	/** The newest artifact of a kind ({@code spec}, {@code diff}, {@code review}, {@code pull-request}), if any. */
	public Mono<RunEvent> latestArtifact(UUID runId, String kind) {
		return get(runId).then(store.latestEvents(runId, Set.of(RunEventType.ARTIFACT_PRODUCED), 200)
				.filter(e -> kind.equals(e.payload().get("kind")))
				.last()
				.onErrorResume(java.util.NoSuchElementException.class, e -> Mono.empty()));
	}

	/**
	 * Every event after {@code afterSeq}, then new ones as they are written, in order and without gaps.
	 * Completes once the run has reached a terminal state and its final events were delivered.
	 */
	public Flux<RunEvent> follow(UUID runId, long afterSeq) {
		return get(runId).flatMapMany(view -> {
			AtomicLong last = new AtomicLong(afterSeq);
			Flux<RunEvent> drain = Flux.defer(() -> drainFrom(runId, last));
			if (view.run().state().isTerminal()) {
				return drain;
			}
			Flux<Object> triggers = Flux.merge(
					Flux.just((Object) "initial"),
					signals.changes().filter(runId::equals),
					Flux.interval(fallbackPoll));
			return triggers
					.onBackpressureLatest()
					.concatMap(trigger -> drain, 1)
					.takeUntil(RunQueries::isTerminalTransition);
		});
	}

	/** Reads pages until the log is exhausted, advancing {@code last} as events are emitted. */
	private Flux<RunEvent> drainFrom(UUID runId, AtomicLong last) {
		return store.events(runId, last.get(), MAX_PAGE)
				.collectList()
				.flatMapMany(page -> {
					if (page.isEmpty()) {
						return Flux.empty();
					}
					last.set(page.getLast().seq());
					Flux<RunEvent> current = Flux.fromIterable(page);
					return page.size() < MAX_PAGE ? current : current.concatWith(Flux.defer(() -> drainFrom(runId, last)));
				});
	}

	private static boolean isTerminalTransition(RunEvent event) {
		if (event.type() != RunEventType.STATE_CHANGED) {
			return false;
		}
		Object to = event.payload().get("to");
		return to != null && RunState.valueOf(to.toString()).isTerminal();
	}

	private static int clamp(int limit) {
		return Math.clamp(limit, 1, MAX_PAGE);
	}
}
