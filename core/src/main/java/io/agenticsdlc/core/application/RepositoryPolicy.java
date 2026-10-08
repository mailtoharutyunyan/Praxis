package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.RepositoryRef;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which SCM hosts runs may clone from. The host clones with real credentials, so an unrestricted URL in task text
 * (especially from tickets) would let anyone point it at internal services.
 */
public record RepositoryPolicy(Set<String> allowedHosts) {

	public RepositoryPolicy {
		allowedHosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
		if (allowedHosts.isEmpty()) {
			throw new IllegalArgumentException("at least one allowed SCM host is required");
		}
	}

	public void check(RepositoryRef repository) {
		String host = repository.cloneUrl().getHost().toLowerCase(Locale.ROOT);
		if (!allowedHosts.contains(host)) {
			throw new IllegalArgumentException("repository host " + host + " is not allowed");
		}
	}
}
