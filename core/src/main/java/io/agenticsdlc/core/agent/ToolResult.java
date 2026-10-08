package io.agenticsdlc.core.agent;

import java.util.Objects;

/** What a tool returned to the model. Errors are returned, not thrown, so the model can correct itself. */
public record ToolResult(String callId, String name, String content, boolean error) {

	public ToolResult {
		Objects.requireNonNull(callId, "callId");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(content, "content");
	}
}
