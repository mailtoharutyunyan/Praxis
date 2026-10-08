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

	/**
	 * @param roleModels a different model for some roles ({@code planner}, {@code reviewer}, {@code triage})
	 * @param pricing applies to every model of the choice; null to look each up in the price list
	 */
	record Choice(AgenticProperties.Provider provider, String model, java.util.Map<String, String> roleModels,
			AgenticProperties.Pricing pricing) {

		public Choice(AgenticProperties.Provider provider, String model) {
			this(provider, model, java.util.Map.of(), null);
		}
	}
}
