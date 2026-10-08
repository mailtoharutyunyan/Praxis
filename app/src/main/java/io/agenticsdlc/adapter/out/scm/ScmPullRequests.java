package io.agenticsdlc.adapter.out.scm;

import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.scm.PullRequests;
import java.util.EnumMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/** {@link PullRequests} for all supported hosts, dispatching on the task's {@link ScmKind}. */
public class ScmPullRequests implements PullRequests {

	/** One code host's REST API. Implementations must make {@link #open} idempotent per source branch. */
	interface Provider {
		Mono<PullRequest> open(RepoCoordinates repo, OpenRequest request);

		Mono<PullRequestState> state(RepoCoordinates repo, PullRequest pullRequest);
	}

	private final Map<ScmKind, Provider> providers;
	private final java.time.Duration retryBackoff;

	public ScmPullRequests(ScmHttp http, boolean draft) {
		this(http, draft, java.time.Duration.ofSeconds(2));
	}

	ScmPullRequests(ScmHttp http, boolean draft, java.time.Duration retryBackoff) {
		this.retryBackoff = retryBackoff;
		providers = new EnumMap<>(ScmKind.class);
		providers.put(ScmKind.GITHUB, new GitHubProvider(http, draft));
		providers.put(ScmKind.GITLAB, new GitLabProvider(http, draft));
		providers.put(ScmKind.BITBUCKET, new BitbucketProvider(http, draft));
		providers.put(ScmKind.AZURE_DEVOPS, new AzureDevOpsProvider(http, draft));
	}

	/**
	 * Find-or-create, repeated as a whole on transient failures or conflicts: if a create timed out after the host made
	 * the pull request, the next attempt's lookup finds it instead of failing on "already exists".
	 */
	@Override
	public Mono<PullRequest> open(RunView view, OpenRequest request) {
		return Mono.defer(() -> {
			RepoCoordinates repo = RepoCoordinates.of(view.task().repository());
			return providers.get(repo.kind()).open(repo, request);
		}).retryWhen(Retry.backoff(3, retryBackoff).filter(ScmHttp::retryableOperation)
				.onRetryExhaustedThrow((spec, signal) -> signal.failure()));
	}

	@Override
	public Mono<PullRequestState> state(RunView view, PullRequest pullRequest) {
		return Mono.defer(() -> {
			RepoCoordinates repo = RepoCoordinates.of(view.task().repository());
			return providers.get(repo.kind()).state(repo, pullRequest);
		});
	}
}
