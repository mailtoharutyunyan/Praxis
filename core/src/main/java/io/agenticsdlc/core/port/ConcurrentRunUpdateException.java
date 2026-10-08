package io.agenticsdlc.core.port;

import java.util.UUID;

/** The run changed since it was read (another worker, an approval, a cancel). Reload and decide again. */
public class ConcurrentRunUpdateException extends RuntimeException {

	private final UUID runId;

	public ConcurrentRunUpdateException(UUID runId, long expectedVersion) {
		super("run " + runId + " was modified concurrently (expected version " + expectedVersion + ")");
		this.runId = runId;
	}

	public UUID runId() {
		return runId;
	}
}
