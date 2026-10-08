package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.domain.RunView;
import reactor.core.publisher.Mono;

/** Pull/merge requests on the task's code host (GitHub, GitLab, Bitbucket, Azure DevOps). Merging stays human. */
public interface PullRequests {

	/** Returns the existing pull request from {@code branch} if there is one, else opens it. Idempotent. */
	Mono<PullRequest> open(RunView view, OpenRequest request);

	Mono<PullRequestState> state(RunView view, PullRequest pullRequest);

	record OpenRequest(String branch, String baseBranch, String title, String body) {
	}

	/** @param id provider identifier: number (GitHub, Bitbucket, Azure DevOps) or IID (GitLab) */
	record PullRequest(String id, String url) {
	}

	enum PullRequestState {
		OPEN,
		MERGED,
		/** Closed, declined or abandoned without merging. */
		CLOSED
	}
}
