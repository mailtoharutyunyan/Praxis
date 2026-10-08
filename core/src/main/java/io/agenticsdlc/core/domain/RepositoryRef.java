package io.agenticsdlc.core.domain;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * Target repository. Only HTTPS clone URLs are accepted: the host authenticates with provider tokens,
 * and SSH/file/git schemes would let task input reach local paths or arbitrary hosts.
 */
public record RepositoryRef(ScmKind kind, URI cloneUrl) {

	private static final Set<String> ALLOWED_SCHEMES = Set.of("https");

	public RepositoryRef {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(cloneUrl, "cloneUrl");
		if (cloneUrl.getScheme() == null || !ALLOWED_SCHEMES.contains(cloneUrl.getScheme().toLowerCase())) {
			throw new IllegalArgumentException("cloneUrl must use https");
		}
		if (cloneUrl.getHost() == null || cloneUrl.getHost().isBlank()) {
			throw new IllegalArgumentException("cloneUrl must have a host");
		}
		if (cloneUrl.getUserInfo() != null) {
			throw new IllegalArgumentException("cloneUrl must not embed credentials");
		}
	}

	/** One key per repository however its URL is spelled: host and path, lower case, without {@code .git}. */
	public String key() {
		String path = cloneUrl.getPath() == null ? "" : cloneUrl.getPath();
		path = path.replaceAll("/+$", "").replaceAll("\\.git$", "");
		return (cloneUrl.getHost() + path).toLowerCase(java.util.Locale.ROOT);
	}
}
