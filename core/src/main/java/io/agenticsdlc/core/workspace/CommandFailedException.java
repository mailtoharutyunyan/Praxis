package io.agenticsdlc.core.workspace;

import java.util.Objects;

/**
 * A streamed sandbox command ({@link Sandbox#execLines}) exited with a non-zero code or timed out. Its standard output
 * was already delivered; {@link #result()} holds the exit code and the tail of its standard error.
 */
public final class CommandFailedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient CommandResult result;

	public CommandFailedException(CommandResult result) {
		super((result.timedOut() ? "timed out after " + result.took().toSeconds() + "s"
				: "exit code " + result.exitCode()) + (result.output().isBlank() ? "" : ": " + result.tail(500)));
		this.result = Objects.requireNonNull(result, "result");
	}

	/** {@link CommandResult#output()} is standard error only. */
	public CommandResult result() {
		return result;
	}
}
