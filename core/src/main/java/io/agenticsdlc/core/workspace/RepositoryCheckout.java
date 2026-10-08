package io.agenticsdlc.core.workspace;

import io.agenticsdlc.core.domain.RunView;
import java.util.Optional;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * The run's local working copy, managed on the host where SCM credentials live. Git metadata is kept outside the
 * directory the sandbox can write, so nothing the agent writes (hooks, filters, config) runs on the host
 * (ADR-0003).
 */
public interface RepositoryCheckout {

	/** Clone the task's repository and create the run's work branch; on later calls reuse it. Idempotent. */
	Mono<CheckoutInfo> checkout(RunView view);

	/** Unified diff of the working tree against the base commit, including new files. Empty if unchanged. */
	Mono<String> diff(UUID runId);

	/**
	 * A text file as it is in the base commit, read on the host; empty if the base has no such file. Paths are relative
	 * to the work tree, companions' under their directory. Errors if the base has something other than a text file
	 * there.
	 */
	default Mono<Optional<String>> baseFile(UUID runId, String path) {
		return Mono.error(new UnsupportedOperationException("this checkout cannot read the base commit"));
	}

	/** Delete the working copy and its git metadata. Idempotent. */
	Mono<Void> remove(UUID runId);

	/**
	 * Checks, without changing anything, that the configured credentials may push to every repository of the run, so a
	 * read-only token stops the run before any model work instead of at publishing.
	 *
	 * @return errors with {@link PushAccessDeniedException} when the code host refuses
	 */
	default Mono<Void> verifyPushAccess(RunView view) {
		return Mono.empty();
	}
}
