package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.domain.RunView;
import reactor.core.publisher.Mono;

/**
 * Commits the run's working copy to its work branch and pushes it, from the host where credentials live. Only the
 * PUBLISHING stage calls this, after a human approved the PUBLISH gate; it is never an agent tool (ADR-0003).
 */
public interface ChangePublisher {

	/** Idempotent: if nothing changed since the last commit it pushes the existing commit again. */
	Mono<PushedBranch> commitAndPush(RunView view, String message);

	record PushedBranch(String branch, String baseBranch, String commit) {
	}
}
