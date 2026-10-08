package io.agenticsdlc.core.workspace;

import java.util.Map;
import java.util.Objects;

/**
 * One environment of a run's sandbox: its name, toolchain image and extra environment. Resource limits, network mode
 * and user are deployment policy and set by the adapter's configuration, not per run.
 *
 * @param name {@link #MAIN} for the environment agents use by default; otherwise a service's name
 */
public record SandboxSpec(String name, String image, Map<String, String> env) {

	public static final String MAIN = "main";

	public SandboxSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(image, "image");
		if (image.isBlank()) {
			throw new IllegalArgumentException("image must not be blank");
		}
		if (!name.matches("[a-z0-9][a-z0-9-]{0,39}")) {
			throw new IllegalArgumentException("environment name must be lower case letters, digits and dashes: " + name);
		}
		env = env == null ? Map.of() : Map.copyOf(env);
	}

	public SandboxSpec(String image, Map<String, String> env) {
		this(MAIN, image, env);
	}
}
