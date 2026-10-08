package io.agenticsdlc.core.workspace;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of a sandbox command.
 *
 * @param output combined stdout/stderr; when {@code truncated}, the middle was cut and the head and tail kept,
 *        because build failures show up at the end
 */
public record CommandResult(String command, int exitCode, String output, boolean truncated, boolean timedOut,
		Duration took) {

	public CommandResult {
		Objects.requireNonNull(command, "command");
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(took, "took");
	}

	public boolean succeeded() {
		return exitCode == 0 && !timedOut;
	}

	/** Last {@code maxChars} of the output, for summaries and model prompts. */
	public String tail(int maxChars) {
		return output.length() <= maxChars ? output : "…" + output.substring(output.length() - maxChars);
	}

	/** Event payload for {@code COMMAND_OUTPUT}. */
	public Map<String, Object> toPayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("command", command);
		payload.put("exitCode", exitCode);
		payload.put("timedOut", timedOut);
		payload.put("durationMs", took.toMillis());
		payload.put("truncated", truncated);
		payload.put("output", output);
		return payload;
	}
}
