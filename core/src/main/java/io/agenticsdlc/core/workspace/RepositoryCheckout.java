package io.agenticsdlc.core.workspace;

import io.agenticsdlc.core.domain.RunView;
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

	/** Delete the working copy and its git metadata. Idempotent. */
	Mono<Void> remove(UUID runId);
}
