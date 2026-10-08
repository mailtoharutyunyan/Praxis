package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.Gate;
import java.util.UUID;

/** Four-eyes rule: the person who requested a task may not approve its gates (when enabled). */
public class SelfApprovalException extends RuntimeException {

	public SelfApprovalException(UUID runId, Gate gate) {
		super("the requester of run " + runId + " may not decide gate " + gate);
	}
}
