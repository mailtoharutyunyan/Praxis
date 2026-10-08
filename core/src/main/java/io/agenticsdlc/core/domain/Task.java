package io.agenticsdlc.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A unit of work as received from a source, normalised. Immutable: corrections create a new run, not an edited task.
 *
 * @param externalRef the source's own id (Jira key, issue number); null for prompts
 * @param baseBranch branch to start from; null means the repository's default branch
 * @param idempotencyKey client-supplied key; a repeated submission with the same key and requester
 *        returns the original task instead of creating a second one. Null when not supplied.
 */
public record Task(
		UUID id,
		TaskOrigin origin,
		String externalRef,
		String title,
		String description,
		RepositoryRef repository,
		String baseBranch,
		Trust trust,
		String requestedBy,
		String idempotencyKey,
		Instant createdAt) {

	public static final int MAX_TITLE_LENGTH = 500;
	public static final int MAX_DESCRIPTION_LENGTH = 100_000;
	public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

	public Task {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(trust, "trust");
		Objects.requireNonNull(requestedBy, "requestedBy");
		Objects.requireNonNull(createdAt, "createdAt");
		title = requireText(title, "title", MAX_TITLE_LENGTH);
		description = requireText(description, "description", MAX_DESCRIPTION_LENGTH);
		if (idempotencyKey != null) {
			idempotencyKey = requireText(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);
		}
	}

	private static String requireText(String value, String name, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		if (value.length() > maxLength) {
			throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
		}
		return value.strip();
	}
}
