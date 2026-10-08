package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.domain.RunView;
import java.util.List;
import reactor.core.publisher.Mono;

/** Pull/merge requests on the task's code host (GitHub, GitLab, Bitbucket, Azure DevOps). Merging stays human. */
public interface PullRequests {

	/** Returns the existing pull request from {@code branch} if there is one, else opens it. Idempotent. */
	Mono<PullRequest> open(RunView view, OpenRequest request);

	Mono<PullRequestState> state(RunView view, PullRequest pullRequest);

	/** Posts a comment on the pull request, e.g. to answer a reviewer. Hosts without support do nothing. */
	default Mono<Void> comment(RunView view, PullRequest pullRequest, String text) {
		return Mono.empty();
	}

	/**
	 * Whether {@code user} may push to the task's repository: its login on GitHub, its numeric id on GitLab. Only
	 * such users can ask the agent for changes from a pull request comment. False when it cannot be determined.
	 */
	default Mono<Boolean> canWrite(RunView view, String user) {
		return Mono.just(false);
	}

	/**
	 * The failed jobs of a CI pipeline (GitHub workflow run id, GitLab pipeline id), each with the end of its log.
	 * Empty when the host is not supported.
	 */
	default Mono<List<FailedJob>> failedJobs(RunView view, String pipelineId) {
		return Mono.just(List.of());
	}

	record OpenRequest(String branch, String baseBranch, String title, String body) {
	}

	/** A failed CI job: its name, a link to it, and the tail of its log (the part that usually explains the failure). */
	record FailedJob(String name, String url, String logTail) {
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
