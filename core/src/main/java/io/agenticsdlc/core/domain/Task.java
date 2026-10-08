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
 * @param companions other repositories changed in the same run (ADR-0006); empty for most tasks
 * @param reviewPlan the requester asked to review the specification even when triage rates the task low risk
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
		Instant createdAt,
		java.util.List<Companion> companions,
		boolean reviewPlan) {

	public static final int MAX_COMPANIONS = 10;

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
		companions = companions == null ? java.util.List.of() : java.util.List.copyOf(companions);
		if (companions.size() > MAX_COMPANIONS) {
			throw new IllegalArgumentException("at most " + MAX_COMPANIONS + " companion repositories");
		}
		if (companions.stream().map(Companion::alias).distinct().count() != companions.size()) {
			throw new IllegalArgumentException("companion aliases must be unique");
		}
		RepositoryRef primary = repository;
		if (companions.stream().anyMatch(c -> c.repository().key().equals(primary.key()))) {
			throw new IllegalArgumentException("a companion repository must differ from the primary one");
		}
	}

	public Task(UUID id, TaskOrigin origin, String externalRef, String title, String description,
			RepositoryRef repository, String baseBranch, Trust trust, String requestedBy, String idempotencyKey,
			Instant createdAt) {
		this(id, origin, externalRef, title, description, repository, baseBranch, trust, requestedBy, idempotencyKey,
				createdAt, java.util.List.of());
	}

	public Task(UUID id, TaskOrigin origin, String externalRef, String title, String description,
			RepositoryRef repository, String baseBranch, Trust trust, String requestedBy, String idempotencyKey,
			Instant createdAt, java.util.List<Companion> companions) {
		this(id, origin, externalRef, title, description, repository, baseBranch, trust, requestedBy, idempotencyKey,
				createdAt, companions, false);
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
