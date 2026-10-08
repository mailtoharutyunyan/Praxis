package io.agenticsdlc.core.workspace;

/** The code host refused to let the configured credentials push to a run's repository. */
public final class PushAccessDeniedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PushAccessDeniedException(String repository) {
		super("The code host refused a push to " + repository + " with the configured token. Give the token write access "
				+ "to this repository (a GitHub fine-grained token needs Contents: Read and write, and Pull requests: "
				+ "Read and write), then resume the run.");
	}
}
