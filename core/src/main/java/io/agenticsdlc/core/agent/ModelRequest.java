package io.agenticsdlc.core.agent;

import java.util.List;
import java.util.Objects;

/**
 * Input of one model turn.
 *
 * @param system instructions; stable across turns so providers can cache them
 */
public record ModelRequest(String system, List<AgentMessage> messages, List<ToolSpec> tools, int maxOutputTokens) {

	public ModelRequest {
		Objects.requireNonNull(system, "system");
		messages = List.copyOf(messages);
		tools = List.copyOf(tools);
		if (messages.isEmpty()) {
			throw new IllegalArgumentException("at least one message is required");
		}
		if (maxOutputTokens <= 0) {
			throw new IllegalArgumentException("maxOutputTokens must be positive");
		}
	}
}
