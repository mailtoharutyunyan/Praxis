package io.agenticsdlc.core.agent;

import io.agenticsdlc.core.domain.Usage;
import java.util.List;
import java.util.Objects;

/**
 * Output of one model turn.
 *
 * @param stopReason provider stop/finish reason, for diagnostics (e.g. {@code end_turn}, {@code max_tokens})
 * @param nativeMessage provider message to replay in later turns (see {@link AgentMessage.Assistant}); may be null
 */
public record ModelReply(String text, List<ToolCall> toolCalls, Usage usage, String stopReason, Object nativeMessage) {

	public ModelReply {
		text = text == null ? "" : text;
		toolCalls = List.copyOf(toolCalls);
		Objects.requireNonNull(usage, "usage");
	}

	public ModelReply(String text, List<ToolCall> toolCalls, Usage usage, String stopReason) {
		this(text, toolCalls, usage, stopReason, null);
	}
}
