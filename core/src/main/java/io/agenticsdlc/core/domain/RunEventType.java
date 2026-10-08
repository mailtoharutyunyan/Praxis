package io.agenticsdlc.core.domain;

/** Kinds of {@link RunEvent}. Stored by name, so only append new constants; never rename. */
public enum RunEventType {
	RUN_CREATED,
	STATE_CHANGED,
	TRIAGED,
	GATE_OPENED,
	GATE_DECIDED,
	STAGE_STARTED,
	STAGE_COMPLETED,
	AGENT_MESSAGE,
	TOOL_CALLED,
	TOOL_RESULT,
	COMMAND_OUTPUT,
	ARTIFACT_PRODUCED,
	USAGE_RECORDED,
	ERROR,
	RISK_RAISED
}
