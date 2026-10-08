package io.agenticsdlc.core.domain;

/** An approver's answer at a {@link Gate}. */
public enum GateDecision {
	APPROVE,
	/** Send the run back to the gate's rework stage; the approver's feedback is recorded as an event. */
	REQUEST_CHANGES,
	/** Stop the run for good. */
	REJECT
}
