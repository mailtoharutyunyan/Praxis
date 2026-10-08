package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.stage.RunHistory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * PUBLISHING: runs only after a human approved the PUBLISH gate. Commits and pushes the work branch, opens (or finds)
 * the pull request, and records it as a {@code pull-request} artifact. Both steps are idempotent, so a retry after a
 * lost lease never creates a second branch or pull request.
 */
public final class PublishStage implements StageHandler {

	public static final String PULL_REQUEST = "pull-request";
	static final int MAX_BODY_CHARS = 60_000;

	private final ChangePublisher publisher;
	private final PullRequests pullRequests;

	public PublishStage(ChangePublisher publisher, PullRequests pullRequests) {
		this.publisher = Objects.requireNonNull(publisher, "publisher");
		this.pullRequests = Objects.requireNonNull(pullRequests, "pullRequests");
	}

	@Override
	public RunState stage() {
		return RunState.PUBLISHING;
	}

	@Override
	public Mono<StageOutcome> execute(StageContext context) {
		Task task = context.task();
		return context.history().map(RunHistory::new).flatMap(history -> publisher
				.commitAndPush(context.view(), commitMessage(context))
				.flatMap(pushed -> pullRequests.open(context.view(), new PullRequests.OpenRequest(pushed.branch(),
						pushed.baseBranch(), title(task), body(context, history)))
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
						})));
	}

	static String title(Task task) {
		String title = task.externalRef() == null ? task.title() : task.externalRef() + ": " + task.title();
		return title.length() <= 250 ? title : title.substring(0, 250);
	}

	static String commitMessage(StageContext context) {
		Task task = context.task();
		return title(task) + "\n\n" + abbreviate(task.description(), 2_000) + "\n\nAgentic-SDLC-Run: " + context.run().id();
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
