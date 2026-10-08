package io.agenticsdlc.core.intake;

import java.util.Objects;
import java.util.Set;

/**
 * An issue in a ticket system, normalised.
 *
 * @param description plain text (converted from the system's rich-text format)
 * @param url human-facing link to the ticket
 */
public record Ticket(String key, String projectKey, String title, String description, Set<String> labels, String url) {

	public Ticket {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(projectKey, "projectKey");
		Objects.requireNonNull(title, "title");
		description = description == null ? "" : description;
		labels = labels == null ? Set.of() : Set.copyOf(labels);
	}
}
