package io.agenticsdlc.adapter.out.scm;

import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.scm.PullRequests;
import java.util.EnumMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/** {@link PullRequests} for all supported hosts, dispatching on the task's {@link ScmKind}. */
public class ScmPullRequests implements PullRequests {

	/** One code host's REST API. Implementations must make {@link #open} idempotent per source branch. */
	interface Provider {
		Mono<PullRequest> open(RepoCoordinates repo, OpenRequest request);

		Mono<PullRequestState> state(RepoCoordinates repo, PullRequest pullRequest);
	}

	private final Map<ScmKind, Provider> providers;

	public ScmPullRequests(ScmHttp http, boolean draft) {
		providers = new EnumMap<>(ScmKind.class);
		providers.put(ScmKind.GITHUB, new GitHubProvider(http, draft));
		providers.put(ScmKind.GITLAB, new GitLabProvider(http, draft));
		providers.put(ScmKind.BITBUCKET, new BitbucketProvider(http, draft));
		providers.put(ScmKind.AZURE_DEVOPS, new AzureDevOpsProvider(http, draft));
	}

	@Override
	public Mono<PullRequest> open(RunView view, OpenRequest request) {
		return Mono.defer(() -> {
			RepoCoordinates repo = RepoCoordinates.of(view.task().repository());
			return providers.get(repo.kind()).open(repo, request);
		});
	}

	@Override
	public Mono<PullRequestState> state(RunView view, PullRequest pullRequest) {
		return Mono.defer(() -> {
			RepoCoordinates repo = RepoCoordinates.of(view.task().repository());
			return providers.get(repo.kind()).state(repo, pullRequest);
		});
	}
}
