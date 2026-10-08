package io.agenticsdlc.core.domain;

import java.util.Objects;

/**
 * Another repository a task changes together with its primary one, e.g. the consumers of an API (ADR-0006). Its
 * working copy is at {@code .repos/<alias>/} inside the primary one, with its own branch and pull request.
 *
 * @param alias directory name and short name in pull requests; lower case letters, digits, dashes and dots
 * @param baseBranch branch to start from; null for the repository's default
 */
public record Companion(String alias, RepositoryRef repository, String baseBranch) {

	public Companion {
		Objects.requireNonNull(repository, "repository");
		if (alias == null || !alias.matches("[a-z0-9][a-z0-9.-]{0,39}")) {
			throw new IllegalArgumentException("companion alias must be 1-40 lower case letters, digits, dots or dashes: "
					+ alias);
		}
	}

	/** The working copy's path relative to the primary repository. */
	public String path() {
		return ".repos/" + alias;
	}

	/** An alias from the clone URL's last segment, e.g. {@code https://github.com/acme/web.git} gives {@code web}. */
	public static String aliasFor(RepositoryRef repository) {
		String path = repository.cloneUrl().getPath() == null ? "" : repository.cloneUrl().getPath();
		String last = path.replaceAll("/+$", "").replaceAll("\\.git$", "");
		last = last.substring(last.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT)
				.replaceAll("[^a-z0-9.-]+", "-").replaceAll("^[-.]+", "");
		return last.isEmpty() ? "companion" : last.length() <= 40 ? last : last.substring(0, 40);
	}
}
