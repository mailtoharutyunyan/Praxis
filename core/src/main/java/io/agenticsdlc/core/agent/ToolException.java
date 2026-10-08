package io.agenticsdlc.core.agent;

/** A tool failed in a way the model can fix (bad path, no match, non-unique match). Reported back as a tool error. */
public class ToolException extends RuntimeException {

	public ToolException(String message) {
		super(message);
	}
}
