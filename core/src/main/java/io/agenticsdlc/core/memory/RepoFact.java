package io.agenticsdlc.core.memory;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Something an agent learned about a repository that later runs should know (a build quirk, a convention, where
 * things live), with the lines of code that show it. Citations are re-checked against the code before each use, so
 * a fact the code no longer supports is not used.
 *
 * @param repository {@link #key repository key}
 * @param sourceRunId the run that learned it; the fact becomes {@link Status#ACTIVE} when that run's pull request is
 *        merged, or when an approver activates it
 */
public record RepoFact(UUID id, String repository, String fact, List<Citation> citations, Status status,
		UUID sourceRunId, Instant createdAt, Instant expiresAt) {

	public static final int MAX_FACT_CHARS = 300;

	public enum Status {
		CANDIDATE, ACTIVE, DISABLED
	}

	/** A line of code supporting the fact; {@code snippet} is that line's text when the fact was stored. */
	public record Citation(String path, int line, String snippet) {
		public Citation {
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(snippet, "snippet");
		}
	}

	public RepoFact {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(status, "status");
		if (fact == null || fact.isBlank() || fact.length() > MAX_FACT_CHARS) {
			throw new IllegalArgumentException("a fact is one sentence of at most " + MAX_FACT_CHARS + " characters");
		}
		citations = List.copyOf(citations);
		if (citations.isEmpty()) {
			throw new IllegalArgumentException("a fact needs at least one citation");
		}
	}

	/** One key per repository however its clone URL is spelled: host and path, lower case, without {@code .git}. */
	public static String key(URI cloneUrl) {
		return new io.agenticsdlc.core.domain.RepositoryRef(io.agenticsdlc.core.domain.ScmKind.GITHUB, cloneUrl).key();
	}
}
