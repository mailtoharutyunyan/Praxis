package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.RepositoryRef;
import java.util.List;
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

	/** A branch pushed to one of the run's repositories; {@code alias} is null for the primary one. */
	record RepositoryPush(String alias, RepositoryRef repository, PushedBranch branch) {
	}

	/**
	 * Commits and pushes every repository of the run that has changes: the primary one and its companions
	 * (ADR-0006). A repository whose work branch has no commits beyond its base is skipped. Idempotent.
	 */
	default Mono<List<RepositoryPush>> commitAndPushAll(RunView view, String message) {
		return commitAndPush(view, message).map(branch -> List.of(new RepositoryPush(null, view.task().repository(), branch)));
	}
}
