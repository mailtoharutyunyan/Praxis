package io.agenticsdlc.core.agent;

import java.util.Objects;

/**
 * A tool as the model sees it.
 *
 * @param inputSchema JSON Schema of the arguments object, as JSON text
 */
public record ToolSpec(String name, String description, String inputSchema) {

	public ToolSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(inputSchema, "inputSchema");
		if (!name.matches("[a-z][a-z0-9_]{0,63}")) {
			throw new IllegalArgumentException("tool name must be snake_case: " + name);
		}
	}
}
