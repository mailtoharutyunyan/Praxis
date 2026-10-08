package io.agenticsdlc.core.workspace;

import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Isolated execution environment of one run: a container with the run's checkout mounted at {@link #WORKDIR}.
 * It holds no credentials; everything that talks to SCM or ticket systems runs outside it (ADR-0003).
 */
public interface Sandbox {

	String WORKDIR = "/workspace";

	/** Start the run's sandbox, or reattach to it if it already runs. Idempotent. */
	Mono<Void> start(UUID runId, SandboxSpec spec);

	/** Run a shell command in {@link #WORKDIR}. Output is stdout and stderr combined, possibly truncated. */
	Mono<CommandResult> exec(UUID runId, String command, Duration timeout);

	/** Stop and remove the sandbox. Idempotent. */
	Mono<Void> destroy(UUID runId);
}
