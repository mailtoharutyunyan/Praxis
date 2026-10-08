package io.agenticsdlc.adapter.out.llm;

import io.agenticsdlc.config.AgenticProperties;
import java.util.Optional;

/**
 * A provider and model chosen at runtime (in the UI) for every role, replacing {@code agentic.models}.
 * {@link #version()} changes whenever the choice does, so models built from the old one are dropped.
 */
public interface ModelOverride {

	long version();

	Optional<Choice> current();

	record Choice(AgenticProperties.Provider provider, String model) {
	}
}
