package io.agenticsdlc.core.workspace;

import java.util.Map;
import java.util.Objects;

/**
 * What a run's sandbox needs: the toolchain image and extra environment. Resource limits, network mode and
 * user are deployment policy and set by the adapter's configuration, not per run.
 */
public record SandboxSpec(String image, Map<String, String> env) {

	public SandboxSpec {
		Objects.requireNonNull(image, "image");
		if (image.isBlank()) {
			throw new IllegalArgumentException("image must not be blank");
		}
		env = env == null ? Map.of() : Map.copyOf(env);
	}
}
