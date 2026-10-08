package io.agenticsdlc.core.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a run. Working states are picked up by the worker; waiting states need a human;
 * terminal states never change again.
 * <p>
 * This table is the structural rule only. {@link Run} adds the semantic ones: which gate may open from
 * where, that a gate decision can only lead to that gate's successors, and that a run leaving
 * {@code NEEDS_HUMAN} resumes exactly where it stopped.
 * <p>
 * Stored by name; the V1 migration's check constraint lists every constant, so add new constants with a migration.
 */
public enum RunState {
	RECEIVED,
	TRIAGING,
	PREPARING_CONTEXT,
	SPECIFYING,
	IMPLEMENTING,
	VERIFYING,
	REVIEWING,
	AWAITING_APPROVAL,
	PUBLISHING,
	PR_OPEN,
	NEEDS_HUMAN,
	DONE,
	FAILED,
	CANCELLED;

	private static final Set<RunState> WORKING = Collections.unmodifiableSet(EnumSet.of(
			RECEIVED, TRIAGING, PREPARING_CONTEXT, SPECIFYING, IMPLEMENTING, VERIFYING, REVIEWING, PUBLISHING));

	private static final Set<RunState> TERMINAL = Collections.unmodifiableSet(EnumSet.of(DONE, FAILED, CANCELLED));

	/** States the worker processes without human input. The claimable-runs index must list exactly these. */
	public static Set<RunState> working() {
		return WORKING;
	}

	public boolean isWorking() {
		return WORKING.contains(this);
	}

	public boolean isTerminal() {
		return TERMINAL.contains(this);
	}

	public boolean canTransitionTo(RunState next) {
		if (isTerminal() || next == this) {
			return false;
		}
		if (next == FAILED || next == CANCELLED) {
			return true;
		}
		if (next == NEEDS_HUMAN) {
			return isWorking();
		}
		return successors().contains(next);
	}

	private Set<RunState> successors() {
		return switch (this) {
			case RECEIVED -> EnumSet.of(TRIAGING);
			case TRIAGING -> EnumSet.of(PREPARING_CONTEXT);
			case PREPARING_CONTEXT -> EnumSet.of(SPECIFYING);
			case SPECIFYING -> EnumSet.of(AWAITING_APPROVAL, IMPLEMENTING);
			case IMPLEMENTING -> EnumSet.of(VERIFYING);
			case VERIFYING -> EnumSet.of(IMPLEMENTING, AWAITING_APPROVAL, REVIEWING);
			case REVIEWING -> EnumSet.of(IMPLEMENTING, AWAITING_APPROVAL);
			case AWAITING_APPROVAL -> EnumSet.of(SPECIFYING, IMPLEMENTING, REVIEWING, PUBLISHING);
			case PUBLISHING -> EnumSet.of(PR_OPEN);
			// A revision (review comment, CI failure) sends an open pull request back to implementation.
			case PR_OPEN -> EnumSet.of(DONE, IMPLEMENTING);
			case NEEDS_HUMAN -> EnumSet.copyOf(WORKING);
			case DONE, FAILED, CANCELLED -> EnumSet.noneOf(RunState.class);
		};
	}
}
