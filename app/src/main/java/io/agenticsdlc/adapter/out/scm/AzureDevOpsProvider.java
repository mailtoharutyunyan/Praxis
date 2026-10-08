package io.agenticsdlc.adapter.out.scm;

import io.agenticsdlc.core.scm.PullRequests.OpenRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequestState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Azure DevOps Services incl. legacy {@code *.visualstudio.com} (Git pull requests API, PAT as Basic auth). */
final class AzureDevOpsProvider implements ScmPullRequests.Provider {

	static final String API_VERSION = "api-version=7.1";

	private final ScmHttp http;
	private final boolean draft;

	AzureDevOpsProvider(ScmHttp http, boolean draft) {
		this.http = http;
		this.draft = draft;
	}

	/** The PR endpoints are documented with the repository GUID, and PRs carry no web URL, so resolve both first. */
	private record Repository(String apiBase, String webUrl) {
	}

	@Override
	public Mono<PullRequest> open(RepoCoordinates repo, OpenRequest request) {
		String source = "refs/heads/" + request.branch();
		return repository(repo).flatMap(repository -> {
			String pullRequests = repository.apiBase() + "/pullrequests";
			String find = pullRequests + "?searchCriteria.sourceRefName=" + encode(source)
					+ "&searchCriteria.status=all&" + API_VERSION;
			return http.get(find, headers(repo))
					.flatMap(page -> page.path("value").isArray() && !page.path("value").isEmpty()
							? Mono.just(toPullRequest(repository, page.path("value").get(0))) : Mono.empty())
					.switchIfEmpty(Mono.defer(() -> {
						Map<String, Object> body = new LinkedHashMap<>();
						body.put("sourceRefName", source);
						body.put("targetRefName", "refs/heads/" + request.baseBranch());
						body.put("title", request.title());
						body.put("description", request.body());
						body.put("isDraft", draft);
						return http.post(pullRequests + "?" + API_VERSION, headers(repo), body)
								.map(pr -> toPullRequest(repository, pr));
					}));
		});
	}

	@Override
	public Mono<PullRequestState> state(RepoCoordinates repo, PullRequest pullRequest) {
		return repository(repo)
				.flatMap(repository -> http.get(repository.apiBase() + "/pullrequests/" + pullRequest.id() + "?"
						+ API_VERSION, headers(repo)))
				.map(pr -> switch (pr.path("status").asString()) {
					case "completed" -> PullRequestState.MERGED;
					case "abandoned" -> PullRequestState.CLOSED;
					default -> PullRequestState.OPEN;
				});
	}

	private Mono<Repository> repository(RepoCoordinates repo) {
		String repositories = organizationBase(repo) + "/" + encodePath(repo.project()) + "/_apis/git/repositories/";
		return http.get(repositories + encodePath(repo.name()) + "?" + API_VERSION, headers(repo))
				.map(found -> new Repository(repositories + found.path("id").asString(), found.path("webUrl").asString()));
	}

	private String organizationBase(RepoCoordinates repo) {
		String fallback = repo.host().endsWith(".visualstudio.com") ? "https://" + repo.host()
				: "https://dev.azure.com/" + repo.owner();
		return http.apiBase(repo.host(), fallback);
	}

	private static PullRequest toPullRequest(Repository repository, JsonNode pr) {
		String id = pr.path("pullRequestId").asString();
		return new PullRequest(id, repository.webUrl() + "/pullrequest/" + id);
	}

	private Consumer<HttpHeaders> headers(RepoCoordinates repo) {
		String basic = Base64.getEncoder().encodeToString((":" + http.token(repo.host())).getBytes(StandardCharsets.UTF_8));
		return h -> h.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String encodePath(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
