package io.agenticsdlc.core.agent;

import java.util.Map;
import java.util.Objects;

/**
 * A tool call requested by the model.
 *
 * @param arguments parsed arguments object (JSON values); empty if the model sent invalid JSON
 * @param rawArguments the arguments exactly as the model sent them
 */
public record ToolCall(String id, String name, Map<String, Object> arguments, String rawArguments) {

	public ToolCall {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(name, "name");
		arguments = arguments == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
		rawArguments = rawArguments == null ? "" : rawArguments;
	}

	public String string(String key) {
		Object value = arguments.get(key);
		return value == null ? null : value.toString();
	}

	public String requiredString(String key) {
		String value = string(key);
		if (value == null) {
			throw new ToolException("missing required argument '" + key + "'");
		}
		return value;
	}

	public int integer(String key, int fallback) {
		Object value = arguments.get(key);
		if (value instanceof Number n) {
			return n.intValue();
		}
		if (value != null) {
			try {
				return Integer.parseInt(value.toString().trim());
			}
			catch (NumberFormatException e) {
				throw new ToolException("argument '" + key + "' must be an integer");
			}
		}
		return fallback;
	}

	public boolean bool(String key, boolean fallback) {
		Object value = arguments.get(key);
		return value == null ? fallback : Boolean.parseBoolean(value.toString());
	}
}
