package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.stage.RunHistory;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * PUBLISHING: runs only after a human approved the PUBLISH gate. Commits and pushes the work branch, opens (or finds)
 * the pull request, and records it as a {@code pull-request} artifact. Both steps are idempotent, so a retry after a
 * lost lease never creates a second branch or pull request. Each external write is preceded by a progress event, which
 * the store refuses once the run was cancelled or the lease lost, so a cancel stops publishing.
 * <p>
 * Only the diff a human approved is published: the working copy's diff must match the fingerprint of the last diff
 * artifact (shown at the PUBLISH gate). Otherwise, for example after the workspace was lost with a dead node, the run
 * escalates instead of pushing something nobody reviewed.
 */
public final class PublishStage implements StageHandler {

	public static final String PULL_REQUEST = "pull-request";
	static final String ACTOR = "system:publisher";
	static final int MAX_BODY_CHARS = 60_000;

	private final ChangePublisher publisher;
	private final PullRequests pullRequests;
	private final RepositoryCheckout checkout;

	public PublishStage(ChangePublisher publisher, PullRequests pullRequests, RepositoryCheckout checkout) {
		this.publisher = Objects.requireNonNull(publisher, "publisher");
		this.pullRequests = Objects.requireNonNull(pullRequests, "pullRequests");
		this.checkout = Objects.requireNonNull(checkout, "checkout");
	}

	@Override
	public RunState stage() {
		return RunState.PUBLISHING;
	}

	@Override
	public Mono<StageOutcome> execute(StageContext context) {
		Task task = context.task();
		return Mono.zip(context.history().map(RunHistory::new), checkout.diff(context.run().id()))
				.flatMap(tuple -> {
					RunHistory history = tuple.getT1();
					String diff = tuple.getT2();
					if (diff.isBlank() || !history.latestDiffFingerprint().equals(
							Optional.of(RunHistory.fingerprint(diff)))) {
						return Mono.just((StageOutcome) new StageOutcome.Escalate("the working copy no longer matches "
								+ "the diff approved at the PUBLISH gate (it may have been lost or changed); request "
								+ "changes to re-implement, or cancel the run", Usage.ZERO));
					}
					return publish(context, task, history);
				});
	}

	private Mono<StageOutcome> publish(StageContext context, Task task, RunHistory history) {
		// Deferred: the external writes must not even start unless the fence event before them was accepted.
		return progress(context, "Pushing the work branch.")
				.then(Mono.defer(() -> publisher.commitAndPush(context.view(), commitMessage(context, history))))
				.flatMap(pushed -> progress(context, "Opening the pull request for " + pushed.branch() + ".")
						.then(Mono.defer(() -> pullRequests.open(context.view(), new PullRequests.OpenRequest(
								pushed.branch(), pushed.baseBranch(), title(task), body(context, history)))))
						.flatMap(pr -> {
							Map<String, Object> payload = new LinkedHashMap<>();
							payload.put("kind", PULL_REQUEST);
							payload.put("id", pr.id());
							payload.put("url", pr.url());
							payload.put("branch", pushed.branch());
							payload.put("commit", pushed.commit());
							payload.put("content", pr.url());
							return context.emit(RunEventType.ARTIFACT_PRODUCED, "system", payload)
									.thenReturn((StageOutcome) new StageOutcome.Completed(Usage.ZERO,
											Map.of("pullRequest", pr.url(), "commit", pushed.commit())));
						}));
	}

	private static Mono<Void> progress(StageContext context, String message) {
		return context.emit(RunEventType.AGENT_MESSAGE, ACTOR, Map.of("text", message));
	}

	static String title(Task task) {
		String title = task.externalRef() == null ? task.title() : task.externalRef() + ": " + task.title();
		return title.length() <= 250 ? title : title.substring(0, 250);
	}

	static String commitMessage(StageContext context, RunHistory history) {
		Task task = context.task();
		return history.currentRevision()
				.map(revision -> "Address " + revision.source() + " feedback: " + firstLine(revision.text()) + "\n\n"
						+ abbreviate(revision.text(), 2_000))
				.orElseGet(() -> title(task) + "\n\n" + abbreviate(task.description(), 2_000))
				+ "\n\nAgentic-SDLC-Run: " + context.run().id();
	}

	private static String firstLine(String text) {
		String line = text.strip().lines().findFirst().orElse("");
		return line.length() <= 72 ? line : line.substring(0, 72) + "…";
	}

	static String body(StageContext context, RunHistory history) {
		Task task = context.task();
		StringBuilder body = new StringBuilder();
		body.append("Implemented by Agentic SDLC run `").append(context.run().id()).append("`")
				.append(" (risk: ").append(context.run().risk()).append(").\n\n");
		body.append("## Task\n").append(abbreviate(task.description(), 8_000)).append("\n\n");
		history.latestArtifact(RunHistory.SPEC)
				.ifPresent(spec -> body.append("<details><summary>Specification</summary>\n\n").append(abbreviate(spec, 20_000))
						.append("\n\n</details>\n\n"));
		history.latestArtifact(RunHistory.REVIEW)
				.ifPresent(review -> body.append("<details><summary>Automated review</summary>\n\n")
						.append(abbreviate(review, 20_000)).append("\n\n</details>\n\n"));
		body.append("Publishing was approved by a human; merging is left to the repository's reviewers.");
		return abbreviate(body.toString(), MAX_BODY_CHARS);
	}

	private static String abbreviate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "\n…";
	}
}
