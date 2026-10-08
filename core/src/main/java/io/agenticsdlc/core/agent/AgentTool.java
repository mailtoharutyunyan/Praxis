package io.agenticsdlc.core.agent;

import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * A capability offered to the model. Tools only touch the run's sandbox or read its working copy; nothing that
 * writes to SCM, tickets or chat is ever a tool (ADR-0003, Rule of Two).
 */
public interface AgentTool {

	ToolSpec spec();

	/** Execute and return the text shown to the model. Throw {@link ToolException} for recoverable mistakes. */
	Mono<String> execute(UUID runId, ToolCall call);

	/** Whether the tool can change the workspace; reviewers only get read-only tools. */
	boolean mutates();
}
