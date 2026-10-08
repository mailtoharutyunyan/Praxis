package io.agenticsdlc.core.application;

import java.util.UUID;

public class RunNotFoundException extends RuntimeException {

	public RunNotFoundException(UUID runId) {
		super("run " + runId + " not found");
	}
}
