package io.agenticsdlc.core.agent;

import java.util.List;
import java.util.Objects;

/** Conversation history in provider-neutral form. */
public sealed interface AgentMessage {

	record User(String text) implements AgentMessage {
		public User {
			Objects.requireNonNull(text, "text");
		}
	}

	/**
	 * @param nativeMessage the provider's own message object, replayed unchanged by the adapter that produced it so
	 *        provider state such as signed thinking blocks survives the round trip; null if built here
	 */
	record Assistant(String text, List<ToolCall> toolCalls, Object nativeMessage) implements AgentMessage {
		public Assistant {
			text = text == null ? "" : text;
			toolCalls = List.copyOf(toolCalls);
		}

		public Assistant(String text, List<ToolCall> toolCalls) {
			this(text, toolCalls, null);
		}
	}

	record ToolResults(List<ToolResult> results) implements AgentMessage {
		public ToolResults {
			results = List.copyOf(results);
		}
	}
}
