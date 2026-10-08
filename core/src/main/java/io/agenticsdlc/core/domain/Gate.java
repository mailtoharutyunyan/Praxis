package io.agenticsdlc.core.domain;

/**
 * A point where a run stops until a human with the approver role decides. Each gate can only be opened
 * from one stage and can only lead to its own successors, so no decision can skip a later gate.
 */
public enum Gate {
	/** Approve requirements, design and task list before any code is written. */
	SPEC(RunState.SPECIFYING, RunState.SPECIFYING, RunState.IMPLEMENTING),
	/** Approve the verified diff before the independent review. */
	IMPLEMENTATION(RunState.VERIFYING, RunState.IMPLEMENTING, RunState.REVIEWING),
	/**
	 * Approve commit, push and pull-request creation, shown with the diff and the reviewer's verdict.
	 * Always required. Merging is never automated.
	 */
	PUBLISH(RunState.REVIEWING, RunState.IMPLEMENTING, RunState.PUBLISHING);

	private final RunState openedFrom;
	private final RunState onChangesRequested;
	private final RunState onApproved;

	Gate(RunState openedFrom, RunState onChangesRequested, RunState onApproved) {
		this.openedFrom = openedFrom;
		this.onChangesRequested = onChangesRequested;
		this.onApproved = onApproved;
	}

	/** The only stage whose completion may open this gate. */
	public RunState openedFrom() {
		return openedFrom;
	}

	/** Stage the run returns to when the approver asks for changes. */
	public RunState onChangesRequested() {
		return onChangesRequested;
	}

	/** Stage the run continues with once approved. */
	public RunState onApproved() {
		return onApproved;
	}

	public RunState next(GateDecision decision) {
		return switch (decision) {
			case APPROVE -> onApproved;
			case REQUEST_CHANGES -> onChangesRequested;
			case REJECT -> RunState.CANCELLED;
		};
	}
}
