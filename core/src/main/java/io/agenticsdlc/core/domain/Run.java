package io.agenticsdlc.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One attempt to carry a {@link Task} to a pull request. Immutable; every change returns a new instance.
 * <p>
 * {@code version} is owned by the repository: inserted as 0, incremented on every successful update
 * ({@code WHERE version = :expected}), and the repository returns the stored instance. Core never changes it.
 *
 * @param risk null until triaged
 * @param gatePolicy null until triaged
 * @param pendingGate set exactly while {@code state == AWAITING_APPROVAL}
 * @param resumeState set exactly while {@code state == NEEDS_HUMAN}: the working stage to continue with
 */
public record Run(
		UUID id,
		UUID taskId,
		RunState state,
		RiskLevel risk,
		GatePolicy gatePolicy,
		Gate pendingGate,
		RunState resumeState,
		int fixIterations,
		int reviewLoops,
		Usage usage,
		long version,
		Instant createdAt,
		Instant updatedAt) {

	public Run {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(taskId, "taskId");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(usage, "usage");
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(updatedAt, "updatedAt");
		if ((state == RunState.AWAITING_APPROVAL) != (pendingGate != null)) {
			throw new IllegalArgumentException("pendingGate must be set exactly when awaiting approval");
		}
		if ((state == RunState.NEEDS_HUMAN) != (resumeState != null)) {
			throw new IllegalArgumentException("resumeState must be set exactly when a human is needed");
		}
		if (resumeState != null && !resumeState.isWorking()) {
			throw new IllegalArgumentException("resumeState must be a working state");
		}
		if ((risk == null) != (gatePolicy == null)) {
			throw new IllegalArgumentException("risk and gatePolicy are set together by triage");
		}
		if (fixIterations < 0 || reviewLoops < 0 || version < 0) {
			throw new IllegalArgumentException("counters must not be negative");
		}
	}

	public static Run start(UUID id, UUID taskId, Instant now) {
		return new Run(id, taskId, RunState.RECEIVED, null, null, null, null, 0, 0, Usage.ZERO, 0, now, now);
	}

	/**
	 * Move along the pipeline. Gates, escalation and resumption have dedicated methods because they carry
	 * extra rules; from a waiting state only FAILED and CANCELLED are reachable here.
	 */
	public Run transitionTo(RunState next, Instant now) {
		if (next == RunState.AWAITING_APPROVAL || next == RunState.NEEDS_HUMAN) {
			throw new IllegalStateException("use awaitApproval/escalate to enter " + next);
		}
		boolean waiting = state == RunState.AWAITING_APPROVAL || state == RunState.NEEDS_HUMAN;
		if (waiting && next != RunState.FAILED && next != RunState.CANCELLED) {
			throw new IllegalStateException("use decide/resume to leave " + state);
		}
		requireTransition(next);
		return with(next, null, null, now);
	}

	/** Stop at {@code gate}. Only a gate the policy requires, and only from the stage that gate follows. */
	public Run awaitApproval(Gate gate, Instant now) {
		Objects.requireNonNull(gate, "gate");
		if (gatePolicy == null || !gatePolicy.requires(gate)) {
			throw new IllegalStateException("run " + id + " policy does not require gate " + gate);
		}
		if (state != gate.openedFrom()) {
			throw new IllegalStateException("gate " + gate + " opens only after " + gate.openedFrom() + ", not " + state);
		}
		requireTransition(RunState.AWAITING_APPROVAL);
		return with(RunState.AWAITING_APPROVAL, gate, null, now);
	}

	/**
	 * Apply an approver's decision. The caller names the gate it decided on, so a decision made on a stale
	 * view of the run cannot be applied to a different gate.
	 */
	public Run decide(Gate gate, GateDecision decision, Instant now) {
		Objects.requireNonNull(gate, "gate");
		Objects.requireNonNull(decision, "decision");
		if (state != RunState.AWAITING_APPROVAL || pendingGate != gate) {
			throw new IllegalStateException("run " + id + " is not waiting at gate " + gate);
		}
		RunState next = gate.next(decision);
		requireTransition(next);
		return with(next, null, null, now);
	}

	/** Hand the run to a human (budget exhausted, stuck, agent question). Remembers where to continue. */
	public Run escalate(Instant now) {
		requireTransition(RunState.NEEDS_HUMAN);
		return with(RunState.NEEDS_HUMAN, null, state, now);
	}

	/** Continue exactly where the run was escalated; never somewhere else, so no gate can be skipped. */
	public Run resume(Instant now) {
		if (state != RunState.NEEDS_HUMAN) {
			throw new IllegalStateException("run " + id + " is not waiting for a human");
		}
		return with(resumeState, null, null, now);
	}

	/** Record triage. Only once, while triaging. */
	public Run triaged(RiskLevel risk, GatePolicy policy, Instant now) {
		Objects.requireNonNull(risk, "risk");
		Objects.requireNonNull(policy, "policy");
		if (state != RunState.TRIAGING || this.risk != null) {
			throw new IllegalStateException("run " + id + " can only be triaged once, while TRIAGING");
		}
		return new Run(id, taskId, state, risk, policy, pendingGate, resumeState, fixIterations, reviewLoops, usage,
				version, createdAt, now);
	}

	/**
	 * Raise risk after triage, e.g. an approver decides a change is architectural. Risk only goes up and
	 * gates are only added, so overriding can never remove oversight. Not allowed once publishing started.
	 */
	public Run raiseRisk(RiskLevel newRisk, GatePolicy additionalGates, Instant now) {
		Objects.requireNonNull(newRisk, "newRisk");
		Objects.requireNonNull(additionalGates, "additionalGates");
		if (risk == null) {
			throw new IllegalStateException("run " + id + " is not triaged yet");
		}
		if (newRisk.compareTo(risk) <= 0) {
			throw new IllegalArgumentException("risk can only be raised, " + risk + " -> " + newRisk + " is not higher");
		}
		if (state.isTerminal() || state == RunState.PUBLISHING || state == RunState.PR_OPEN) {
			throw new IllegalStateException("run " + id + " is past the point where risk can change");
		}
		return new Run(id, taskId, state, newRisk, gatePolicy.union(additionalGates), pendingGate, resumeState,
				fixIterations, reviewLoops, usage, version, createdAt, now);
	}

	public Run withFixIteration(Instant now) {
		return new Run(id, taskId, state, risk, gatePolicy, pendingGate, resumeState, fixIterations + 1, reviewLoops,
				usage, version, createdAt, now);
	}

	public Run withReviewLoop(Instant now) {
		return new Run(id, taskId, state, risk, gatePolicy, pendingGate, resumeState, fixIterations, reviewLoops + 1,
				usage, version, createdAt, now);
	}

	public Run addUsage(Usage delta, Instant now) {
		return new Run(id, taskId, state, risk, gatePolicy, pendingGate, resumeState, fixIterations, reviewLoops,
				usage.plus(delta), version, createdAt, now);
	}

	private Run with(RunState next, Gate gate, RunState resume, Instant now) {
		return new Run(id, taskId, next, risk, gatePolicy, gate, resume, fixIterations, reviewLoops, usage, version,
				createdAt, now);
	}

	private void requireTransition(RunState next) {
		if (!state.canTransitionTo(next)) {
			throw new IllegalStateException("run " + id + " cannot move from " + state + " to " + next);
		}
	}
}
