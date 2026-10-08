package io.agenticsdlc.core.port;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Task;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Durable storage for tasks, runs and their event log (ADR-0002).
 * <p>
 * Contract every implementation must honour:
 * <ul>
 * <li>A state change and its events are written atomically.</li>
 * <li>Updates are fenced by {@link Run#version()}: the store applies {@code next} only if the stored version still
 * equals {@code current.version()}, increments it, and otherwise fails with {@link ConcurrentRunUpdateException}.</li>
 * <li>Events receive a per-run sequence (1, 2, 3, …) without gaps, in commit order.</li>
 * <li>Claiming a run increments its version, so a worker whose lease was taken over can no longer write.</li>
 * </ul>
 */
public interface RunStore {

	/**
	 * Store a new task with its first run and events. If the requester already submitted a task with the same
	 * idempotency key, nothing is written and the existing task's latest run is returned with {@code created = false}.
	 */
	Mono<Submission> submit(Task task, Run run, List<RunEvent> events);

	Mono<RunView> find(UUID runId);

	/** Newest first by (createdAt, id). {@code before} is an exclusive cursor; null starts from the newest run. */
	Flux<RunView> list(Set<RunState> states, Cursor before, int limit);

	/** Runs from one origin changed after {@code after}, by (updatedAt, id), oldest change first. */
	Flux<RunView> listUpdatedSince(io.agenticsdlc.core.domain.TaskOrigin origin, Cursor after, int limit);

	/** Persist {@code next} if {@code current} is still the stored version; returns the stored instance. */
	Mono<Run> update(Run current, Run next, List<RunEvent> events);

	/**
	 * Append progress events without changing the run. Only the current lease owner may append, and only while the run
	 * is in a working state, so a cancelled run's worker fails here with {@code LeaseLostException}.
	 */
	Mono<Void> append(UUID runId, String leaseOwner, List<RunEvent> events);

	/** Events with {@code seq > afterSeq}, oldest first. */
	Flux<RunEvent> events(UUID runId, long afterSeq, int limit);

	/** The newest {@code limit} events of the given types, oldest first. */
	Flux<RunEvent> latestEvents(UUID runId, Set<io.agenticsdlc.core.domain.RunEventType> types, int limit);

	/** Lease one working run whose lease is free or expired; empty if none is available. */
	Mono<Run> claim(String owner, Duration lease);

	/** Extend a lease still held by {@code owner}; emits false if it was lost or the run left its working states. */
	Mono<Boolean> renewLease(UUID runId, String owner, Duration lease);

	Mono<Void> releaseLease(UUID runId, String owner);

	record Submission(RunView view, boolean created) {
	}

	/**
	 * A position in a run listing: a timestamp plus the run id breaking ties, so runs sharing a timestamp are neither
	 * skipped nor repeated across pages. Without an id only the timestamp counts.
	 */
	record Cursor(Instant at, UUID id) {
		public Cursor {
			java.util.Objects.requireNonNull(at, "at");
		}

		public static Cursor at(Instant at) {
			return new Cursor(at, null);
		}
	}
}
