package io.agenticsdlc.core.agent;

/** The jobs models do in the pipeline; each can be served by a different provider and model. */
public enum AgentRole {
	/** Classifies risk. Cheap and fast is fine. */
	TRIAGE,
	/** Writes the spec: requirements, design notes, task list. */
	PLANNER,
	/** Implements in the sandbox with read/write tools. Use the strongest coding model. */
	CODER,
	/** Reviews the diff against the spec with read-only tools, in a fresh context. */
	REVIEWER
}
