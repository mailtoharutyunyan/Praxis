package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.port.ConcurrentRunUpdateException;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Human actions on a run: gate decisions, cancel, resume, raising risk. Each one re-reads the run, applies the
 * domain rule and stores the result with version fencing; a race with the worker is retried on fresh state.
 */
public final class RunCommands {

	private static final int MAX_CONFLICT_RETRIES = 3;

	private final RunStore store;
	private final Clock clock;
	private final boolean forbidSelfApproval;

	public RunCommands(RunStore store, Clock clock, boolean forbidSelfApproval) {
		this.store = Objects.requireNonNull(store, "store");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.forbidSelfApproval = forbidSelfApproval;
	}

	public Mono<Run> decide(UUID runId, Gate gate, GateDecision decision, String comment, String actor) {
		Objects.requireNonNull(gate, "gate");
		Objects.requireNonNull(decision, "decision");
		return change(runId, (view, now) -> {
			if (forbidSelfApproval && actor.equals(view.task().requestedBy())) {
				throw new SelfApprovalException(runId, gate);
			}
			Run next = view.run().decide(gate, decision, now);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("gate", gate.name());
			payload.put("decision", decision.name());
			payload.put("comment", comment);
			return new Change(next, List.of(event(runId, RunEventType.GATE_DECIDED, actor, payload, now)));
		}, actor);
	}

	public Mono<Run> cancel(UUID runId, String reason, String actor) {
		return change(runId, (view, now) -> {
			Run next = view.run().transitionTo(RunState.CANCELLED, now);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("kind", "CANCELLED");
			payload.put("reason", reason);
			return new Change(next, List.of(event(runId, RunEventType.ERROR, actor, payload, now)));
		}, actor);
	}

	public Mono<Run> resume(UUID runId, String actor) {
		return change(runId, (view, now) -> new Change(view.run().resume(now), List.of()), actor);
	}

	/** The run's pull request was merged (→ DONE) or closed without merging (→ CANCELLED). */
	public Mono<Run> closePullRequest(UUID runId, boolean merged, String actor) {
		return change(runId, (view, now) -> {
			if (view.run().state() != RunState.PR_OPEN) {
				throw new IllegalStateException("run " + runId + " has no open pull request");
			}
			Run next = view.run().transitionTo(merged ? RunState.DONE : RunState.CANCELLED, now);
			Map<String, Object> payload = new LinkedHashMap<>();
			if (merged) {
				payload.put("stage", RunState.PR_OPEN.name());
				payload.put("outcome", "Merged");
			}
			else {
				payload.put("kind", "PULL_REQUEST_CLOSED");
				payload.put("reason", "the pull request was closed without merging");
			}
			return new Change(next, List.of(event(runId, merged ? RunEventType.STAGE_COMPLETED : RunEventType.ERROR,
					actor, payload, now)));
		}, actor);
	}

	public Mono<Run> raiseRisk(UUID runId, RiskLevel risk, String reason, String actor) {
		Objects.requireNonNull(risk, "risk");
		return change(runId, (view, now) -> {
			Run current = view.run();
			Run next = current.raiseRisk(risk, GatePolicy.forRisk(risk, view.task().trust()), now);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("from", current.risk() == null ? null : current.risk().name());
			payload.put("to", risk.name());
			payload.put("gates", next.gatePolicy().gates().stream().map(Enum::name).toList());
			payload.put("reason", reason);
			return new Change(next, List.of(event(runId, RunEventType.RISK_RAISED, actor, payload, now)));
		}, actor);
	}

	private Mono<Run> change(UUID runId, BiFunction<RunView, Instant, Change> rule, String actor) {
		Objects.requireNonNull(actor, "actor");
		return Mono.defer(() -> store.find(runId)
						.switchIfEmpty(Mono.error(() -> new RunNotFoundException(runId)))
						.flatMap(view -> {
							Instant now = clock.instant();
							Change change = rule.apply(view, now);
							List<RunEvent> events = new ArrayList<>(change.events());
							if (change.next().state() != view.run().state()) {
								Map<String, Object> payload = new LinkedHashMap<>();
								payload.put("from", view.run().state().name());
								payload.put("to", change.next().state().name());
								events.add(event(runId, RunEventType.STATE_CHANGED, actor, payload, now));
							}
							return store.update(view.run(), change.next(), events);
						}))
				.retryWhen(Retry.max(MAX_CONFLICT_RETRIES)
						.filter(ConcurrentRunUpdateException.class::isInstance)
						.onRetryExhaustedThrow((spec, signal) -> signal.failure()));
	}

	private static RunEvent event(UUID runId, RunEventType type, String actor, Map<String, Object> payload, Instant at) {
		return RunEvent.of(runId, type, actor, payload, at);
	}

	private record Change(Run next, List<RunEvent> events) {
	}
}
