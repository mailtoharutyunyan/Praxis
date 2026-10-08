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
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Bitbucket Cloud (REST 2.0, repository or workspace access token as Bearer). */
final class BitbucketProvider implements ScmPullRequests.Provider {

	/** Pipelines build links: {@code .../addon/pipelines/home#!/results/<n>} or {@code .../pipelines/results/<n>}. */
	private static final Pattern PIPELINE_RESULT = Pattern.compile("/pipelines/(?:home#!/)?results/(\\d+)");
	private static final Set<String> FAILED_STEP = Set.of("FAILED", "ERROR");

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

	@Override
	public Mono<Void> comment(RepoCoordinates repo, PullRequest pullRequest, String text) {
		return http.post(repository(repo) + "/pullrequests/" + pullRequest.id() + "/comments", headers(repo),
				Map.of("content", Map.of("raw", text))).then();
	}

	/**
	 * Effective write or admin permission, including group and workspace grants. {@code user} is the account UUID
	 * ({@code {...}}). Listing permissions needs a token with admin permission on the repository.
	 */
	@Override
	public Mono<Boolean> canWrite(RepoCoordinates repo, String user) {
		String permissions = base(repo) + "/workspaces/" + repo.owner() + "/permissions/repositories/" + repo.name()
				+ "?q=" + encode("user.uuid=\"" + user + "\"");
		return http.get(permissions, headers(repo))
				.map(page -> page.path("values").valueStream()
						.filter(p -> user.equalsIgnoreCase(p.path("user").path("uuid").asString("")))
						.anyMatch(p -> switch (p.path("permission").asString("")) {
							case "admin", "write" -> true;
							default -> false;
						}))
				.onErrorResume(ScmHttp.ScmException.class, e -> e.status().value() == 404 ? Mono.just(false)
						: Mono.error(e));
	}

	/**
	 * The failed steps of a build status, identified as {@code <commit>/<status key>}. A Bitbucket Pipelines build
	 * (its status links to {@code .../pipelines/results/<build number>}) yields each failed step with the end of its
	 * log; a build from another CI server yields the status itself, whose description is all Bitbucket knows.
	 */
	@Override
	public Mono<List<FailedJob>> failedJobs(RepoCoordinates repo, String pipelineId) {
		int slash = pipelineId.indexOf('/');
		if (slash < 0) {
			return Mono.just(List.of());
		}
		String commit = pipelineId.substring(0, slash);
		return http.get(repository(repo) + "/commit/" + encode(commit) + "/statuses/build/"
				+ encode(pipelineId.substring(slash + 1)), headers(repo))
				.flatMap(status -> {
					Matcher pipeline = PIPELINE_RESULT.matcher(status.path("url").asString(""));
					return pipeline.find() ? pipelineSteps(repo, commit, Long.parseLong(pipeline.group(1)))
							: Mono.just(List.of(new FailedJob(status.path("name").asString("build"),
									status.path("url").asString(""), status.path("description").asString(""))));
				});
	}

	private Mono<List<FailedJob>> pipelineSteps(RepoCoordinates repo, String commit, long buildNumber) {
		String pipelines = repository(repo) + "/pipelines";
		return http.get(pipelines + "?target.commit.hash=" + encode(commit) + "&sort=-created_on&pagelen=50",
				headers(repo))
				.flatMapMany(page -> Flux.fromIterable(page.path("values").valueStream()
						.filter(p -> p.path("build_number").asLong(-1) == buildNumber).limit(1).toList()))
				.concatMap(pipeline -> {
					String steps = pipelines + "/" + encode(pipeline.path("uuid").asString()) + "/steps";
					return http.get(steps + "?pagelen=100", headers(repo))
							.flatMapMany(page -> Flux.fromIterable(page.path("values").valueStream()
									.filter(step -> FAILED_STEP.contains(step.path("state").path("result").path("name")
											.asString("")))
									.toList()))
							.take(GitHubProvider.MAX_FAILED_JOBS)
							.concatMap(step -> http.textTail(steps + "/" + encode(step.path("uuid").asString()) + "/log",
											headers(repo), GitHubProvider.LOG_TAIL_BYTES)
									.onErrorResume(e -> Mono.just("(log unavailable: " + e.getMessage() + ")"))
									.map(log -> new FailedJob(step.path("name").asString("step"), "", log)));
				})
				.collectList();
	}

	private String repository(RepoCoordinates repo) {
		return base(repo) + "/repositories/" + repo.owner() + "/" + repo.name();
	}

	private String base(RepoCoordinates repo) {
		return http.apiBase(repo.host(), "https://api.bitbucket.org/2.0");
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
