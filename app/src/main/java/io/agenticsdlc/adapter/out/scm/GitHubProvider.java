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

/** GitHub.com and GitHub Enterprise Server (REST API, {@code X-GitHub-Api-Version: 2022-11-28}). */
final class GitHubProvider implements ScmPullRequests.Provider {

	private final ScmHttp http;
	private final boolean draft;

	static final int MAX_FAILED_JOBS = 5;
	static final int LOG_TAIL_BYTES = 12_000;

	GitHubProvider(ScmHttp http, boolean draft) {
		this.http = http;
		this.draft = draft;
	}

	@Override
	public Mono<PullRequest> open(RepoCoordinates repo, OpenRequest request) {
		String pulls = base(repo) + "/repos/" + repo.owner() + "/" + repo.name() + "/pulls";
		String find = pulls + "?state=all&head=" + encode(repo.owner() + ":" + request.branch());
		return http.get(find, headers(repo))
				.flatMap(found -> found.isArray() && !found.isEmpty() ? Mono.just(toPullRequest(found.get(0)))
						: Mono.empty())
				.switchIfEmpty(Mono.defer(() -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("title", request.title());
					body.put("head", request.branch());
					body.put("base", request.baseBranch());
					body.put("body", request.body());
					body.put("draft", draft);
					return http.post(pulls, headers(repo), body).map(GitHubProvider::toPullRequest);
				}));
	}

	@Override
	public Mono<PullRequestState> state(RepoCoordinates repo, PullRequest pullRequest) {
		return http.get(base(repo) + "/repos/" + repo.owner() + "/" + repo.name() + "/pulls/" + pullRequest.id(),
				headers(repo)).map(pr -> {
					if (pr.path("merged").asBoolean(false) || !pr.path("merged_at").isNull() && !pr.path("merged_at").isMissingNode()) {
						return PullRequestState.MERGED;
					}
					return "closed".equals(pr.path("state").asString()) ? PullRequestState.CLOSED : PullRequestState.OPEN;
				});
	}

	@Override
	public Mono<Void> comment(RepoCoordinates repo, PullRequest pullRequest, String text) {
		return http.post(repository(repo) + "/issues/" + pullRequest.id() + "/comments", headers(repo),
				Map.of("body", text)).then();
	}

	/** Write, maintain or admin permission on the repository. */
	@Override
	public Mono<Boolean> canWrite(RepoCoordinates repo, String user) {
		return http.get(repository(repo) + "/collaborators/" + encode(user) + "/permission", headers(repo))
				.map(found -> switch (found.path("permission").asString("")) {
					case "admin", "write", "maintain" -> true;
					default -> false;
				})
				.onErrorResume(ScmHttp.ScmException.class, e -> e.status().value() == 404 ? Mono.just(false)
						: Mono.error(e));
	}

	/** Failed jobs of a GitHub Actions workflow run, with the failed step names and the end of each job's log. */
	@Override
	public Mono<List<FailedJob>> failedJobs(RepoCoordinates repo, String workflowRunId) {
		return http.get(repository(repo) + "/actions/runs/" + encode(workflowRunId) + "/jobs?filter=latest&per_page=100",
				headers(repo))
				.flatMapMany(page -> Flux.fromIterable(page.path("jobs").valueStream()
						.filter(job -> "failure".equals(job.path("conclusion").asString(""))
								|| "timed_out".equals(job.path("conclusion").asString("")))
						.toList()))
				.take(MAX_FAILED_JOBS)
				.concatMap(job -> http.textTail(repository(repo) + "/actions/jobs/" + job.path("id").asString() + "/logs",
								headers(repo), LOG_TAIL_BYTES)
						.onErrorResume(e -> Mono.just("(log unavailable: " + e.getMessage() + ")"))
						.map(log -> new FailedJob(job.path("name").asString() + failedSteps(job),
								job.path("html_url").asString(""), log)))
				.collectList();
	}

	private static String failedSteps(JsonNode job) {
		List<String> steps = job.path("steps").valueStream()
				.filter(step -> "failure".equals(step.path("conclusion").asString("")))
				.map(step -> step.path("name").asString()).toList();
		return steps.isEmpty() ? "" : " (failed step: " + String.join(", ", steps) + ")";
	}

	private String repository(RepoCoordinates repo) {
		return base(repo) + "/repos/" + repo.owner() + "/" + repo.name();
	}

	private String base(RepoCoordinates repo) {
		return http.apiBase(repo.host(), repo.host().equals("github.com") ? "https://api.github.com"
				: "https://" + repo.host() + "/api/v3");
	}

	private Consumer<HttpHeaders> headers(RepoCoordinates repo) {
		return h -> {
			h.setBearerAuth(http.token(repo.host()));
			h.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
			h.set("X-GitHub-Api-Version", "2022-11-28");
		};
	}

	private static PullRequest toPullRequest(JsonNode pr) {
		return new PullRequest(pr.path("number").asString(), pr.path("html_url").asString());
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
