package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.memory.RepoMemory;
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
	private final RepoMemory memory;
	private final java.time.Clock clock;
	private final java.time.Duration retention;
	private java.util.function.BiConsumer<UUID, Throwable> onError = (run, error) -> {
	};

	public PullRequestTracker(RunStore store, PullRequests pullRequests, RunCommands commands) {
		this(store, pullRequests, commands, null, java.time.Clock.systemUTC(), java.time.Duration.ZERO);
	}

	/**
	 * @param memory facts the run learned become active when its pull request is merged (they are discarded when it
	 *        is closed); null when repository memory is off
	 */
	public PullRequestTracker(RunStore store, PullRequests pullRequests, RunCommands commands, RepoMemory memory,
			java.time.Clock clock, java.time.Duration retention) {
		this.store = Objects.requireNonNull(store, "store");
		this.pullRequests = Objects.requireNonNull(pullRequests, "pullRequests");
		this.commands = Objects.requireNonNull(commands, "commands");
		this.memory = memory;
		this.clock = Objects.requireNonNull(clock, "clock");
		this.retention = Objects.requireNonNull(retention, "retention");
	}

	/**
	 * Called when checking a run's pull requests fails (the code host is down, the token lost access); the sweep goes
	 * on with the next run, so without this a failure would go unnoticed.
	 */
	public PullRequestTracker onError(java.util.function.BiConsumer<UUID, Throwable> listener) {
		this.onError = Objects.requireNonNull(listener, "listener");
		return this;
	}

	/** Checks every open pull request once, page by page; emits the runs that finished. */
	public Flux<UUID> sweep() {
		return page(null)
				.expand(page -> page.size() < BATCH ? Mono.empty() : page(cursorOf(page.getLast())))
				.concatMap(page -> Flux.fromIterable(page).concatMap(view -> check(view).onErrorResume(e -> {
					onError.accept(view.run().id(), e);
					return Mono.empty();
				})));
	}

	private Mono<java.util.List<RunView>> page(RunStore.Cursor before) {
		return store.list(Set.of(RunState.PR_OPEN), before, BATCH).collectList();
	}

	private static RunStore.Cursor cursorOf(RunView view) {
		return new RunStore.Cursor(view.run().createdAt(), view.run().id());
	}

	/**
	 * A run with several pull requests (companion repositories) is DONE when all are merged, and CANCELLED once none
	 * is open but not all were merged.
	 */
	public Mono<UUID> check(RunView view) {
		return store.latestEvents(view.run().id(), Set.of(RunEventType.ARTIFACT_PRODUCED), 200).collectList()
				.map(events -> PullRequestArtifacts.latest(events, view.task().repository()))
				.filter(published -> !published.isEmpty())
				.flatMap(published -> Flux.fromIterable(published)
						.concatMap(p -> pullRequests.state(view, p.pullRequest()))
						.collectList())
				.filter(states -> !states.contains(PullRequests.PullRequestState.OPEN))
				.map(states -> states.stream().allMatch(s -> s == PullRequests.PullRequestState.MERGED))
				.flatMap(merged -> commands.closePullRequest(view.run().id(), merged, ACTOR)
						.flatMap(run -> memory == null ? Mono.just(run)
								: memory.settleRun(run.id(), merged, clock.instant().plus(retention)).thenReturn(run)))
				.map(run -> run.id());
	}
}
