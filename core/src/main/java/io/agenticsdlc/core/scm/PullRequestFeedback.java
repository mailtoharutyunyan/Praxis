package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.application.RevisionRequest;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.port.RunStore;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/**
 * Closes the loop after a pull request is open: a reviewer's comment that mentions the bot, or a failed CI pipeline
 * on the branch, sends the run back for a revision of the same branch (which a human approves again before it is
 * pushed). Only people who can push to the repository can ask for changes, a pipeline counts only for the commit the
 * agent pushed last, and CI fixes per run are capped so a flaky pipeline cannot loop forever.
 */
public final class PullRequestFeedback {

	public static final String ACTOR = "system:scm-feedback";
	static final int MAX_LOG_CHARS_PER_JOB = 8_000;
	private static final Pattern BRANCH = Pattern.compile("agent/([0-9a-fA-F-]{36})");

	private final RunStore store;
	private final RunCommands commands;
	private final PullRequests pullRequests;
	private final String mention;
	private final int maxCiFixes;
	private final String runLinkBase;

	/**
	 * @param mention how reviewers address the bot, e.g. {@code @agentic-sdlc}; matched case-insensitively
	 * @param runLinkBase prefix for links to a run in the UI; null or blank for none
	 */
	public PullRequestFeedback(RunStore store, RunCommands commands, PullRequests pullRequests, String mention,
			int maxCiFixes, String runLinkBase) {
		this.store = Objects.requireNonNull(store, "store");
		this.commands = Objects.requireNonNull(commands, "commands");
		this.pullRequests = Objects.requireNonNull(pullRequests, "pullRequests");
		this.mention = Objects.requireNonNull(mention, "mention").strip();
		if (this.mention.isEmpty()) {
			throw new IllegalArgumentException("mention must not be blank");
		}
		this.maxCiFixes = maxCiFixes;
		this.runLinkBase = runLinkBase;
	}

	/**
	 * A comment on a pull request.
	 *
	 * @param source code host, e.g. {@code github}
	 * @param commentId unique per comment at the host
	 * @param author the commenter's display name or login
	 * @param authorKey what {@link PullRequests#canWrite} needs: the login on GitHub, the numeric id on GitLab
	 */
	public record Comment(String source, String commentId, String pullRequestUrl, String author, String authorKey,
			String body, String location, String url) {
	}

	/**
	 * A failed CI pipeline. {@code branch} is the pushed branch, {@code headSha} the commit it ran on, and
	 * {@code repositoryUrl} the repository's web or clone URL (null: the task's primary repository).
	 */
	public record CiFailure(String source, String pipelineId, String branch, String headSha, String url,
			String repositoryUrl) {
		public CiFailure(String source, String pipelineId, String branch, String headSha, String url) {
			this(source, pipelineId, branch, headSha, url, null);
		}
	}

	public sealed interface Result {
		record Revising(UUID runId) implements Result {
		}

		record Ignored(String reason) implements Result {
		}
	}

	public Mono<Result> onComment(Comment comment) {
		String body = Objects.toString(comment.body(), "");
		if (!body.toLowerCase(Locale.ROOT).contains(mention.toLowerCase(Locale.ROOT))) {
			return ignored("the comment does not mention " + mention);
		}
		String request = Pattern.compile(Pattern.quote(mention), Pattern.CASE_INSENSITIVE).matcher(body).replaceAll("")
				.strip();
		if (request.isEmpty()) {
			return ignored("the comment asks for nothing");
		}
		return store.runWithPullRequest(comment.pullRequestUrl())
				.flatMap(store::find)
				.flatMap(view -> {
					if (view.run().state() != RunState.PR_OPEN) {
						return ignored("the run is " + view.run().state() + ", not waiting on its pull request");
					}
					return published(view).flatMap(all -> {
						PullRequestArtifacts.Published on = all.stream()
								.filter(p -> p.pullRequest().url().equals(comment.pullRequestUrl())).findFirst()
								.orElse(all.getFirst());
						var repository = on.pullRequest().repository() == null ? view.task().repository()
								: on.pullRequest().repository();
						String where = on.pullRequest().repository() == null ? "" : " (in " + repository.cloneUrl() + ")";
						return pullRequests.canWrite(view, repository, comment.authorKey()).flatMap(allowed -> !allowed
								? ignored(comment.author() + " cannot push to this repository")
								: revise(view, new RevisionRequest(comment.source(), comment.source() + ":comment:"
										+ comment.commentId(), comment.author(), request + where, comment.location(),
										comment.url()), comment.source() + ":" + comment.author(), on.pullRequest(),
										"Revising this pull request as asked"));
					});
				})
				.switchIfEmpty(ignored("no run owns this pull request"));
	}

	public Mono<Result> onCiFailure(CiFailure failure) {
		var matcher = BRANCH.matcher(Objects.toString(failure.branch(), ""));
		if (!matcher.matches()) {
			return ignored("not an agent branch: " + failure.branch());
		}
		UUID runId = UUID.fromString(matcher.group(1));
		return store.find(runId).flatMap(view -> {
			if (view.run().state() != RunState.PR_OPEN) {
				return ignored("the run is " + view.run().state() + ", not waiting on its pull request");
			}
			return store.latestEvents(runId, Set.of(RunEventType.ARTIFACT_PRODUCED, RunEventType.REVISION_REQUESTED), 200)
					.collectList()
					.flatMap(events -> {
						List<PullRequestArtifacts.Published> all = PullRequestArtifacts.latest(events, view.task().repository());
						String key = failure.repositoryUrl() == null ? view.task().repository().key()
								: new io.agenticsdlc.core.domain.RepositoryRef(view.task().repository().kind(),
										java.net.URI.create(failure.repositoryUrl())).key();
						Optional<PullRequestArtifacts.Published> on = all.stream().filter(p -> p.repositoryKey().equals(key))
								.findFirst();
						if (on.isEmpty() || !on.get().commit().equalsIgnoreCase(failure.headSha())) {
							return ignored("the pipeline ran on " + failure.headSha() + ", not the last pushed commit");
						}
						var repository = on.get().pullRequest().repository() == null ? view.task().repository()
								: on.get().pullRequest().repository();
						long ciFixes = events.stream().filter(e -> e.type() == RunEventType.REVISION_REQUESTED
								&& "ci".equals(e.payload().get("source"))).count();
						if (ciFixes >= maxCiFixes) {
							return ignored("the run already had " + ciFixes + " CI fixes");
						}
						return pullRequests.failedJobs(view, repository, failure.pipelineId()).flatMap(jobs -> jobs.isEmpty()
								? ignored("no failed jobs found in pipeline " + failure.pipelineId())
								: revise(view, new RevisionRequest("ci", failure.source() + ":pipeline:"
										+ failure.pipelineId(), failure.source() + " CI", ciRequest(failure, jobs)
												+ (on.get().pullRequest().repository() == null ? ""
														: "\n(The failing pipeline is in " + repository.cloneUrl() + ".)"),
										null, failure.url()), ACTOR, on.get().pullRequest(), "CI failed on "
												+ abbreviateSha(failure.headSha()) + "; revising this pull request to fix it"));
					});
		}).switchIfEmpty(ignored("no run " + runId));
	}

	static String ciRequest(CiFailure failure, List<PullRequests.FailedJob> jobs) {
		StringBuilder text = new StringBuilder("CI failed on commit ").append(abbreviateSha(failure.headSha()))
				.append(". Find the cause in these logs and fix the code; change a test only if the test itself is wrong.");
		for (PullRequests.FailedJob job : jobs) {
			String log = job.logTail();
			if (log.length() > MAX_LOG_CHARS_PER_JOB) {
				log = log.substring(log.length() - MAX_LOG_CHARS_PER_JOB);
			}
			text.append("\n\nJob: ").append(job.name()).append(job.url().isBlank() ? "" : " (" + job.url() + ")")
					.append("\nEnd of its log:\n").append(log);
		}
		return text.toString();
	}

	private Mono<Result> revise(RunView view, RevisionRequest request, String actor, PullRequests.PullRequest replyOn,
			String reply) {
		UUID runId = view.run().id();
		return commands.requestRevision(runId, request, actor)
				.then(pullRequests.comment(view, replyOn, reply + " (run " + link(runId)
						+ "). The changes are pushed after a maintainer approves them.").onErrorResume(e -> Mono.empty()))
				.thenReturn((Result) new Result.Revising(runId));
	}

	private Mono<List<PullRequestArtifacts.Published>> published(RunView view) {
		return store.latestEvents(view.run().id(), Set.of(RunEventType.ARTIFACT_PRODUCED), 200).collectList()
				.map(events -> PullRequestArtifacts.latest(events, view.task().repository()))
				.filter(all -> !all.isEmpty());
	}

	private String link(UUID runId) {
		return runLinkBase == null || runLinkBase.isBlank() ? runId.toString() : runLinkBase + runId;
	}

	private static String abbreviateSha(String sha) {
		return sha == null || sha.length() <= 12 ? String.valueOf(sha) : sha.substring(0, 12);
	}

	private static Mono<Result> ignored(String reason) {
		return Mono.just(new Result.Ignored(reason));
	}
}
