package io.agenticsdlc.adapter.out.scm;

import io.agenticsdlc.core.scm.PullRequests.OpenRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequestState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Bitbucket Cloud (REST 2.0, repository or workspace access token as Bearer). */
final class BitbucketProvider implements ScmPullRequests.Provider {

	private final ScmHttp http;
	private final boolean draft;

	BitbucketProvider(ScmHttp http, boolean draft) {
		this.http = http;
		this.draft = draft;
	}

	@Override
	public Mono<PullRequest> open(RepoCoordinates repo, OpenRequest request) {
		String pullRequests = repository(repo) + "/pullrequests";
		String query = "source.branch.name=\"" + request.branch() + "\"";
		String find = pullRequests + "?q=" + encode(query)
				+ "&state=OPEN&state=MERGED&state=DECLINED&state=SUPERSEDED";
		return http.get(find, headers(repo))
				.flatMap(page -> page.path("values").isArray() && !page.path("values").isEmpty()
						? Mono.just(toPullRequest(page.path("values").get(0))) : Mono.empty())
				.switchIfEmpty(Mono.defer(() -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("title", request.title());
					body.put("description", request.body());
					body.put("source", Map.of("branch", Map.of("name", request.branch())));
					body.put("destination", Map.of("branch", Map.of("name", request.baseBranch())));
					body.put("close_source_branch", true);
					body.put("draft", draft);
					return http.post(pullRequests, headers(repo), body).map(BitbucketProvider::toPullRequest);
				}));
	}

	@Override
	public Mono<PullRequestState> state(RepoCoordinates repo, PullRequest pullRequest) {
		return http.get(repository(repo) + "/pullrequests/" + pullRequest.id(), headers(repo))
				.map(pr -> switch (pr.path("state").asString()) {
					case "MERGED" -> PullRequestState.MERGED;
					case "DECLINED", "SUPERSEDED" -> PullRequestState.CLOSED;
					default -> PullRequestState.OPEN;
				});
	}

	private String repository(RepoCoordinates repo) {
		return http.apiBase(repo.host(), "https://api.bitbucket.org/2.0") + "/repositories/" + repo.owner() + "/"
				+ repo.name();
	}

	private Consumer<HttpHeaders> headers(RepoCoordinates repo) {
		return h -> h.setBearerAuth(http.token(repo.host()));
	}

	private static PullRequest toPullRequest(JsonNode pr) {
		return new PullRequest(pr.path("id").asString(), pr.path("links").path("html").path("href").asString());
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
