package io.agenticsdlc.core.workspace;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Isolated execution environment of one run: containers with the run's checkout mounted at {@link #WORKDIR}, one
 * per toolchain environment ({@link SandboxSpec#name}), plus optional sidecars on a private network (ADR-0006).
 * It holds no credentials; everything that talks to SCM or ticket systems runs outside it (ADR-0003).
 */
public interface Sandbox {

	String WORKDIR = "/workspace";

	/** Start one of the run's environments, or reattach to it if it already runs. Idempotent. */
	Mono<Void> start(UUID runId, SandboxSpec spec);

	/**
	 * Start the containers the run's tests depend on, reachable from its environments by name and isolated from
	 * everything else; call before {@link #start}. Idempotent. Sandboxes without support fail if any are asked for.
	 */
	default Mono<Void> startSidecars(UUID runId, List<ProjectConfig.SidecarConfig> sidecars) {
		return sidecars.isEmpty() ? Mono.empty()
				: Mono.error(new UnsupportedOperationException("this sandbox cannot run sidecar containers"));
	}

	/**
	 * Run a shell command in {@link #WORKDIR} of the {@link SandboxSpec#MAIN} environment. Output is stdout and
	 * stderr combined, possibly truncated.
	 */
	Mono<CommandResult> exec(UUID runId, String command, Duration timeout);

	/** Run a shell command in {@link #WORKDIR} of the named environment. */
	default Mono<CommandResult> exec(UUID runId, String environment, String command, Duration timeout) {
		return exec(runId, command, timeout);
	}

	/**
	 * Read a UTF-8 text file. Paths are relative to {@link #WORKDIR}; symlinks resolve inside the container, never
	 * on the host. Errors with {@link java.nio.file.NoSuchFileException} if absent, {@link IllegalArgumentException}
	 * if larger than {@code maxBytes} or not a regular file.
	 */
	Mono<String> readFile(UUID runId, String relativePath, int maxBytes);

	/** Create or replace a UTF-8 text file, creating parent directories. Path relative to {@link #WORKDIR}. */
	Mono<Void> writeFile(UUID runId, String relativePath, String content);

	/** Stop and remove the sandbox. Idempotent. */
	Mono<Void> destroy(UUID runId);
}
