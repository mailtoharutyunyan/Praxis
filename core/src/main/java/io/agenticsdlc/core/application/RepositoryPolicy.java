package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.RepositoryRef;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Which SCM hosts runs may clone from. The host clones with real credentials, so an unrestricted URL in task text
 * (especially from tickets) would let anyone point it at internal services.
 */
public final class RepositoryPolicy {

	private final Supplier<Set<String>> hosts;

	public RepositoryPolicy(Set<String> allowedHosts) {
		Set<String> fixed = normalize(allowedHosts);
		if (fixed.isEmpty()) {
			throw new IllegalArgumentException("at least one allowed SCM host is required");
		}
		this.hosts = () -> fixed;
	}

	/** Hosts read at check time, for allow lists that change at runtime (hosts added in the UI). */
	public RepositoryPolicy(Supplier<Set<String>> allowedHosts) {
		this.hosts = () -> normalize(allowedHosts.get());
	}

	private static Set<String> normalize(Set<String> hosts) {
		return hosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
	}

	public Set<String> allowedHosts() {
		return hosts.get();
	}

	public void check(RepositoryRef repository) {
		String host = repository.cloneUrl().getHost().toLowerCase(Locale.ROOT);
		if (!hosts.get().contains(host)) {
			throw new IllegalArgumentException("repository host " + host + " is not allowed");
		}
	}
}
