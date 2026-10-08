package io.agenticsdlc.core.port;

import java.util.UUID;

/** The worker no longer holds the run's lease; it must stop working on the run. */
public class LeaseLostException extends RuntimeException {

	public LeaseLostException(UUID runId, String owner) {
		super("lease on run " + runId + " is no longer held by " + owner);
	}
}
