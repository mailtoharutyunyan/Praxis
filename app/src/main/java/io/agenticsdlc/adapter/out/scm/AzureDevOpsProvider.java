package io.agenticsdlc.adapter.out.scm;

import io.agenticsdlc.core.scm.PullRequests.FailedJob;
import io.agenticsdlc.core.scm.PullRequests.OpenRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequest;
import io.agenticsdlc.core.scm.PullRequests.PullRequestState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Azure DevOps Services incl. legacy {@code *.visualstudio.com} (Git pull requests API, PAT as Basic auth). */
final class AzureDevOpsProvider implements ScmPullRequests.Provider {

	static final String API_VERSION = "api-version=7.1";
	/** The "Git Repositories" security namespace and its Contribute (push) permission bit. */
	static final String GIT_REPOSITORIES_NAMESPACE = "2e9eb7ed-3c0a-47d4-87c1-0ffdd275fd87";
	static final long CONTRIBUTE = 4;

	private final ScmHttp http;
	private final boolean draft;

	AzureDevOpsProvider(ScmHttp http, boolean draft) {
		this.http = http;
		this.draft = draft;
	}

	/** The PR endpoints are documented with the repository GUID, and PRs carry no web URL, so resolve both first. */
	private record Repository(String apiBase, String webUrl, String id, String projectId) {
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

	/** A new active thread on the pull request with one text comment. */
	@Override
	public Mono<Void> comment(RepoCoordinates repo, PullRequest pullRequest, String text) {
		return repository(repo).flatMap(repository -> http.post(repository.apiBase() + "/pullRequests/"
				+ pullRequest.id() + "/threads?" + API_VERSION, headers(repo), Map.of("status", "active", "comments",
				java.util.List.of(Map.of("parentCommentId", 0, "content", text, "commentType", "text"))))).then();
	}

	/**
	 * Whether the identity (the comment author's id) may contribute to the repository, counting group membership: its
	 * descriptor is looked up, then the repository's access control list is evaluated for it. The token needs the
	 * Identity (read) and Security (manage) scopes.
	 */
	@Override
	public Mono<Boolean> canWrite(RepoCoordinates repo, String user) {
		Mono<String> descriptor = http.get(identityBase(repo) + "/_apis/identities?identityIds=" + encode(user)
				+ "&queryMembership=None&" + API_VERSION, headers(repo))
				.flatMap(found -> Mono.justOrEmpty(found.path("value").valueStream()
						.map(identity -> identity.path("descriptor").asString("")).filter(d -> !d.isBlank()).findFirst()));
		return Mono.zip(repository(repo), descriptor)
				.flatMap(both -> http.get(organizationBase(repo) + "/_apis/accesscontrollists/" + GIT_REPOSITORIES_NAMESPACE
						+ "?token=" + encode("repoV2/" + both.getT1().projectId() + "/" + both.getT1().id())
						+ "&descriptors=" + encode(both.getT2()) + "&includeExtendedInfo=true&" + API_VERSION, headers(repo)))
				.map(acls -> acls.path("value").valueStream()
						.flatMap(acl -> acl.path("acesDictionary").valueStream())
						.map(ace -> ace.path("extendedInfo"))
						.anyMatch(info -> (info.path("effectiveAllow").asLong(0) & CONTRIBUTE) != 0
								&& (info.path("effectiveDeny").asLong(0) & CONTRIBUTE) == 0))
				.defaultIfEmpty(false)
				.onErrorResume(ScmHttp.ScmException.class, e -> e.status().value() == 404 ? Mono.just(false)
						: Mono.error(e));
	}

	/** The failed tasks of a build (its numeric id) from the build's timeline, each with the end of its log. */
	@Override
	public Mono<List<FailedJob>> failedJobs(RepoCoordinates repo, String buildId) {
		String build = organizationBase(repo) + "/" + encodePath(repo.project()) + "/_apis/build/builds/"
				+ encodePath(buildId);
		return http.get(build + "/timeline?" + API_VERSION, headers(repo))
				.flatMapMany(timeline -> {
					Map<String, JsonNode> byId = new LinkedHashMap<>();
					timeline.path("records").valueStream().forEach(r -> byId.put(r.path("id").asString(""), r));
					List<JsonNode> failed = byId.values().stream()
							.filter(r -> "failed".equals(r.path("result").asString("")) && r.path("log").has("id"))
							.toList();
					// A task's log explains the failure; its job's log mostly repeats it.
					List<JsonNode> tasks = failed.stream().filter(r -> "Task".equals(r.path("type").asString(""))).toList();
					return Flux.fromIterable(tasks.isEmpty() ? failed : tasks)
							.take(GitHubProvider.MAX_FAILED_JOBS)
							.concatMap(record -> http.textTail(build + "/logs/" + record.path("log").path("id").asString()
									+ "?" + API_VERSION, headers(repo).andThen(h -> h.setAccept(List.of(MediaType.TEXT_PLAIN))),
									GitHubProvider.LOG_TAIL_BYTES)
									.onErrorResume(e -> Mono.just("(log unavailable: " + e.getMessage() + ")"))
									.map(log -> new FailedJob(recordName(record, byId), "", log)));
				})
				.collectList();
	}

	/** "Job / Task" when the record has a parent. */
	private static String recordName(JsonNode record, Map<String, JsonNode> byId) {
		JsonNode parent = byId.get(record.path("parentId").asString(""));
		String name = record.path("name").asString("task");
		return parent == null ? name : parent.path("name").asString("") + " / " + name;
	}

	/** Identities live on the organization's vssps host. */
	private String identityBase(RepoCoordinates repo) {
		String fallback = repo.host().endsWith(".visualstudio.com") ? "https://" + repo.owner() + ".vssps.visualstudio.com"
				: "https://vssps.dev.azure.com/" + repo.owner();
		return http.apiBase(repo.host(), fallback);
	}

	private Mono<Repository> repository(RepoCoordinates repo) {
		String repositories = organizationBase(repo) + "/" + encodePath(repo.project()) + "/_apis/git/repositories/";
		return http.get(repositories + encodePath(repo.name()) + "?" + API_VERSION, headers(repo))
				.map(found -> new Repository(repositories + found.path("id").asString(), found.path("webUrl").asString(),
						found.path("id").asString(), found.path("project").path("id").asString()));
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
