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

	/** Newest first. {@code createdBefore} is an exclusive cursor; null starts from the newest run. */
	Flux<RunView> list(Set<RunState> states, Instant createdBefore, int limit);

	/** Runs from one origin updated after {@code since}, oldest change first. */
	Flux<RunView> listUpdatedSince(io.agenticsdlc.core.domain.TaskOrigin origin, Instant since, int limit);

	/** Persist {@code next} if {@code current} is still the stored version; returns the stored instance. */
	Mono<Run> update(Run current, Run next, List<RunEvent> events);

	/** Append progress events without changing the run. Only the current lease owner may append. */
	Mono<Void> append(UUID runId, String leaseOwner, List<RunEvent> events);

	/** Events with {@code seq > afterSeq}, oldest first. */
	Flux<RunEvent> events(UUID runId, long afterSeq, int limit);

	/** Lease one working run whose lease is free or expired; empty if none is available. */
	Mono<Run> claim(String owner, Duration lease);

	/** Extend a lease still held by {@code owner}; emits false if it was lost. */
	Mono<Boolean> renewLease(UUID runId, String owner, Duration lease);

	Mono<Void> releaseLease(UUID runId, String owner);

	record Submission(RunView view, boolean created) {
	}
}
