package io.agenticsdlc.core.support;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.port.ConcurrentRunUpdateException;
import io.agenticsdlc.core.port.LeaseLostException;
import io.agenticsdlc.core.port.RunChangeSignals;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Test double honouring the {@link RunStore} contract: version fencing, gap-free sequences, leases, idempotency. */
public final class InMemoryRunStore implements RunStore, RunChangeSignals {

	private final Clock clock;
	private final Map<UUID, Task> tasks = new LinkedHashMap<>();
	private final Map<UUID, Run> runs = new LinkedHashMap<>();
	private final Map<UUID, List<RunEvent>> events = new LinkedHashMap<>();
	private final Map<UUID, Lease> leases = new LinkedHashMap<>();
	private final Sinks.Many<UUID> changes = Sinks.many().multicast().directBestEffort();
	private int renewalFailures;

	private record Lease(String owner, Instant expiresAt) {
	}

	public InMemoryRunStore(Clock clock) {
		this.clock = clock;
	}

	@Override
	public synchronized Mono<Submission> submit(Task task, Run run, List<RunEvent> newEvents) {
		if (task.idempotencyKey() != null) {
			for (Task existing : tasks.values()) {
				if (task.idempotencyKey().equals(existing.idempotencyKey())
						&& task.requestedBy().equals(existing.requestedBy())) {
					Run latest = runs.values().stream().filter(r -> r.taskId().equals(existing.id()))
							.max(Comparator.comparing(Run::createdAt)).orElseThrow();
					return Mono.just(new Submission(new RunView(latest, existing), false));
				}
			}
		}
		tasks.put(task.id(), task);
		runs.put(run.id(), run);
		events.put(run.id(), new ArrayList<>());
		appendLocked(run.id(), newEvents);
		return Mono.just(new Submission(new RunView(run, task), true));
	}

	@Override
	public synchronized Mono<RunView> find(UUID runId) {
		Run run = runs.get(runId);
		return run == null ? Mono.empty() : Mono.just(new RunView(run, tasks.get(run.taskId())));
	}

	@Override
	public synchronized Flux<RunView> list(Set<RunState> states, Instant createdBefore, int limit) {
		List<RunView> views = runs.values().stream()
				.filter(r -> states.isEmpty() || states.contains(r.state()))
				.filter(r -> createdBefore == null || r.createdAt().isBefore(createdBefore))
				.sorted(Comparator.comparing(Run::createdAt).reversed())
				.limit(limit)
				.map(r -> new RunView(r, tasks.get(r.taskId())))
				.toList();
		return Flux.fromIterable(views);
	}

	@Override
	public synchronized Flux<RunView> listUpdatedSince(io.agenticsdlc.core.domain.TaskOrigin origin, Instant since,
			int limit) {
		return Flux.fromIterable(runs.values().stream()
				.filter(r -> tasks.get(r.taskId()).origin() == origin && r.updatedAt().isAfter(since))
				.sorted(Comparator.comparing(Run::updatedAt))
				.limit(limit)
				.map(r -> new RunView(r, tasks.get(r.taskId())))
				.toList());
	}

	@Override
	public synchronized Mono<Run> update(Run current, Run next, List<RunEvent> newEvents) {
		Run stored = runs.get(current.id());
		if (stored == null || stored.version() != current.version()) {
			return Mono.error(new ConcurrentRunUpdateException(current.id(), current.version()));
		}
		Run saved = withVersion(next, stored.version() + 1);
		runs.put(saved.id(), saved);
		appendLocked(saved.id(), newEvents);
		return Mono.just(saved);
	}

	@Override
	public synchronized Mono<Void> append(UUID runId, String leaseOwner, List<RunEvent> newEvents) {
		Lease lease = leases.get(runId);
		if (lease == null || !lease.owner().equals(leaseOwner) || !runs.get(runId).state().isWorking()) {
			return Mono.error(new LeaseLostException(runId, leaseOwner));
		}
		appendLocked(runId, newEvents);
		return Mono.empty();
	}

	@Override
	public synchronized Flux<RunEvent> events(UUID runId, long afterSeq, int limit) {
		return Flux.fromIterable(events.getOrDefault(runId, List.of()).stream()
				.filter(e -> e.seq() > afterSeq).limit(limit).toList());
	}

	@Override
	public synchronized Mono<Run> claim(String owner, Duration lease) {
		Instant now = clock.instant();
		for (Run run : runs.values()) {
			Lease held = leases.get(run.id());
			if (run.state().isWorking() && (held == null || held.expiresAt().isBefore(now))) {
				leases.put(run.id(), new Lease(owner, now.plus(lease)));
				Run claimed = withVersion(run, run.version() + 1);
				runs.put(run.id(), claimed);
				return Mono.just(claimed);
			}
		}
		return Mono.empty();
	}

	@Override
	public synchronized Mono<Boolean> renewLease(UUID runId, String owner, Duration lease) {
		if (renewalFailures > 0) {
			renewalFailures--;
			return Mono.error(new IllegalStateException("simulated database error"));
		}
		Lease held = leases.get(runId);
		if (held == null || !held.owner().equals(owner) || !runs.get(runId).state().isWorking()) {
			return Mono.just(false);
		}
		leases.put(runId, new Lease(owner, clock.instant().plus(lease)));
		return Mono.just(true);
	}

	@Override
	public synchronized Mono<Void> releaseLease(UUID runId, String owner) {
		Lease held = leases.get(runId);
		if (held != null && held.owner().equals(owner)) {
			leases.remove(runId);
		}
		return Mono.empty();
	}

	@Override
	public Flux<UUID> changes() {
		return changes.asFlux();
	}

	/** Test hook: take a lease away, as if it expired and another worker claimed the run. */
	/** The next {@code count} renewals fail as if the database were briefly unreachable. */
	public synchronized void failRenewals(int count) {
		renewalFailures = count;
	}

	public synchronized int pendingRenewalFailures() {
		return renewalFailures;
	}

	public synchronized void stealLease(UUID runId, String newOwner) {
		leases.put(runId, new Lease(newOwner, clock.instant().plusSeconds(60)));
		Run run = runs.get(runId);
		runs.put(runId, withVersion(run, run.version() + 1));
	}

	public synchronized Run run(UUID runId) {
		return Objects.requireNonNull(runs.get(runId));
	}

	public synchronized List<RunEvent> allEvents(UUID runId) {
		return List.copyOf(events.get(runId));
	}

	public synchronized boolean leased(UUID runId) {
		return leases.containsKey(runId);
	}

	private void appendLocked(UUID runId, List<RunEvent> newEvents) {
		List<RunEvent> log = events.get(runId);
		for (RunEvent e : newEvents) {
			log.add(new RunEvent((long) log.size() + 1, e.runId(), e.type(), e.actor(), e.payload(), e.occurredAt()));
		}
		if (!newEvents.isEmpty()) {
			changes.tryEmitNext(runId);
		}
	}

	private static Run withVersion(Run r, long version) {
		return new Run(r.id(), r.taskId(), r.state(), r.risk(), r.gatePolicy(), r.pendingGate(), r.resumeState(),
				r.fixIterations(), r.reviewLoops(), r.usage(), version, r.createdAt(), r.updatedAt());
	}
}
