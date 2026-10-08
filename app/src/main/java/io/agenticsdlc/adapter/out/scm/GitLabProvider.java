package io.agenticsdlc.adapter.out.scm;

import io.agenticsdlc.core.scm.PullRequests.FailedJob;
import io.agenticsdlc.core.scm.PullRequests.OpenRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequestState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** GitLab.com and self-managed GitLab (REST v4 merge requests; the project is addressed by its encoded path). */
final class GitLabProvider implements ScmPullRequests.Provider {

	private final ScmHttp http;
	private final boolean draft;

	GitLabProvider(ScmHttp http, boolean draft) {
		this.http = http;
		this.draft = draft;
	}

	@Override
	public Mono<PullRequest> open(RepoCoordinates repo, OpenRequest request) {
		String mergeRequests = project(repo) + "/merge_requests";
		return http.get(mergeRequests + "?state=all&source_branch=" + encode(request.branch()), headers(repo))
				.flatMap(found -> found.isArray() && !found.isEmpty() ? Mono.just(toPullRequest(found.get(0)))
						: Mono.empty())
				.switchIfEmpty(Mono.defer(() -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("source_branch", request.branch());
					body.put("target_branch", request.baseBranch());
					body.put("title", draft ? "Draft: " + request.title() : request.title());
					body.put("description", request.body());
					body.put("remove_source_branch", true);
					return http.post(mergeRequests, headers(repo), body).map(GitLabProvider::toPullRequest);
				}));
	}

	@Override
	public Mono<PullRequestState> state(RepoCoordinates repo, PullRequest pullRequest) {
		return http.get(project(repo) + "/merge_requests/" + pullRequest.id(), headers(repo))
				.map(mr -> switch (mr.path("state").asString()) {
					case "merged" -> PullRequestState.MERGED;
					case "closed" -> PullRequestState.CLOSED;
					// "locked" is transient: GitLab holds the merge request while it merges it.
					default -> PullRequestState.OPEN;
				});
	}

	@Override
	public Mono<Void> comment(RepoCoordinates repo, PullRequest pullRequest, String text) {
		return http.post(project(repo) + "/merge_requests/" + pullRequest.id() + "/notes", headers(repo),
				Map.of("body", text)).then();
	}

	/** Developer access or above (level 30), including inherited group membership. {@code user} is the numeric id. */
	@Override
	public Mono<Boolean> canWrite(RepoCoordinates repo, String user) {
		return http.get(project(repo) + "/members/all/" + encode(user), headers(repo))
				.map(member -> member.path("access_level").asInt(0) >= 30)
				.onErrorResume(ScmHttp.ScmException.class, e -> e.status().value() == 404 ? Mono.just(false)
						: Mono.error(e));
	}

	@Override
	public Mono<List<FailedJob>> failedJobs(RepoCoordinates repo, String pipelineId) {
		return http.get(project(repo) + "/pipelines/" + encode(pipelineId) + "/jobs?scope%5B%5D=failed&per_page=100",
				headers(repo))
				.flatMapMany(jobs -> Flux.fromIterable(jobs.valueStream().toList()))
				.take(GitHubProvider.MAX_FAILED_JOBS)
				.concatMap(job -> http.textTail(project(repo) + "/jobs/" + job.path("id").asString() + "/trace",
								headers(repo), GitHubProvider.LOG_TAIL_BYTES)
						.onErrorResume(e -> Mono.just("(log unavailable: " + e.getMessage() + ")"))
						.map(log -> new FailedJob(job.path("name").asString() + " (stage " + job.path("stage").asString()
								+ ")", job.path("web_url").asString(""), log)))
				.collectList();
	}

	private String project(RepoCoordinates repo) {
		String base = http.apiBase(repo.host(), "https://" + repo.host() + "/api/v4");
		return base + "/projects/" + encode(repo.owner() + "/" + repo.name());
	}

	private Consumer<HttpHeaders> headers(RepoCoordinates repo) {
		return h -> h.set("PRIVATE-TOKEN", http.token(repo.host()));
	}

	private static PullRequest toPullRequest(JsonNode mr) {
		return new PullRequest(mr.path("iid").asString(), mr.path("web_url").asString());
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
