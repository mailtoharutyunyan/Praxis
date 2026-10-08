package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.port.RunStore;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Follows runs in PR_OPEN: a merged pull request completes the run (DONE), one closed without merging cancels it.
 * Polling keeps it provider-agnostic; webhooks can call {@link #check} directly for faster updates.
 */
public final class PullRequestTracker {

	public static final String ACTOR = "system:scm";
	static final int BATCH = 200;

	private final RunStore store;
	private final PullRequests pullRequests;
	private final RunCommands commands;

	public PullRequestTracker(RunStore store, PullRequests pullRequests, RunCommands commands) {
		this.store = Objects.requireNonNull(store, "store");
		this.pullRequests = Objects.requireNonNull(pullRequests, "pullRequests");
		this.commands = Objects.requireNonNull(commands, "commands");
	}

	/** Checks every open pull request once, page by page; emits the runs that finished. */
	public Flux<UUID> sweep() {
		return page(null)
				.expand(page -> page.size() < BATCH ? Mono.empty() : page(cursorOf(page.getLast())))
				.concatMap(page -> Flux.fromIterable(page).concatMap(view -> check(view).onErrorResume(e -> Mono.empty())));
	}

	private Mono<java.util.List<RunView>> page(RunStore.Cursor before) {
		return store.list(Set.of(RunState.PR_OPEN), before, BATCH).collectList();
	}

	private static RunStore.Cursor cursorOf(RunView view) {
		return new RunStore.Cursor(view.run().createdAt(), view.run().id());
	}

	public Mono<UUID> check(RunView view) {
		return pullRequestOf(view.run().id())
				.flatMap(pr -> pullRequests.state(view, pr))
				.filter(state -> state != PullRequests.PullRequestState.OPEN)
				.flatMap(state -> commands.closePullRequest(view.run().id(), state == PullRequests.PullRequestState.MERGED,
						ACTOR))
				.map(run -> run.id());
	}

	private Mono<PullRequests.PullRequest> pullRequestOf(UUID runId) {
		return store.latestEvents(runId, Set.of(RunEventType.ARTIFACT_PRODUCED), 50)
				.filter(e -> PublishStage.PULL_REQUEST.equals(e.payload().get("kind")))
				.last()
				.map(PullRequestTracker::toPullRequest)
				.onErrorResume(java.util.NoSuchElementException.class, e -> Mono.empty());
	}

	private static PullRequests.PullRequest toPullRequest(RunEvent event) {
		return new PullRequests.PullRequest(String.valueOf(event.payload().get("id")),
				String.valueOf(event.payload().get("url")));
	}
}
