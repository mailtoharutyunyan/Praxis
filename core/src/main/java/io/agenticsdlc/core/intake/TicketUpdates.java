package io.agenticsdlc.core.intake;

import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reports progress of ticket-originated runs back to the ticket as comments: started, waiting at a gate, pull request
 * opened, needs a human, finished. A per-run cursor makes each event reported once (at-least-once if a comment
 * succeeds but the cursor write fails — acceptable for status comments).
 */
public final class TicketUpdates {

	static final int PAGE = 200;

	private final RunStore store;
	private final TicketSystem tickets;
	private final TicketSyncStore cursors;
	private final TaskOrigin origin;
	private final Clock clock;
	private final Duration lookback;
	private final String runLinkBase;

	/**
	 * @param lookback runs updated within this window are checked on each sweep
	 * @param runLinkBase prefix for links to a run in the UI or API, e.g. {@code https://agentic.example.com/runs/};
	 *        null for no link
	 */
	public TicketUpdates(RunStore store, TicketSystem tickets, TicketSyncStore cursors, TaskOrigin origin, Clock clock,
			Duration lookback, String runLinkBase) {
		this.store = Objects.requireNonNull(store, "store");
		this.tickets = Objects.requireNonNull(tickets, "tickets");
		this.cursors = Objects.requireNonNull(cursors, "cursors");
		this.origin = Objects.requireNonNull(origin, "origin");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.lookback = Objects.requireNonNull(lookback, "lookback");
		this.runLinkBase = runLinkBase;
	}

	/**
	 * One pass over every recently changed run from this ticket system, page by page (no run is starved however many
	 * there are); emits how many comments were posted per run.
	 */
	public Flux<Integer> sweep() {
		return page(RunStore.Cursor.at(clock.instant().minus(lookback)))
				.expand(page -> page.size() < PAGE ? Mono.empty()
						: page(new RunStore.Cursor(page.getLast().run().updatedAt(), page.getLast().run().id())))
				.concatMap(page -> Flux.fromIterable(page).concatMap(view -> report(view).onErrorResume(e -> Mono.just(0))));
	}

	private Mono<java.util.List<RunView>> page(RunStore.Cursor after) {
		return store.listUpdatedSince(origin, after, PAGE).collectList();
	}

	Mono<Integer> report(RunView view) {
		UUID runId = view.run().id();
		String key = view.task().externalRef();
		if (key == null) {
			return Mono.just(0);
		}
		return cursors.lastReported(runId).flatMap(after -> store.events(runId, after, 200).collectList())
				.flatMap(events -> Flux.fromIterable(events)
						.concatMap(event -> message(view, event, events)
								.map(text -> tickets.comment(key, text, link(runId)).thenReturn(1))
								.orElse(Mono.just(0))
								.flatMap(posted -> cursors.markReported(runId, event.seq()).thenReturn(posted)))
						.reduce(0, Integer::sum));
	}

	/** The comment for an event, if it is worth telling the ticket about. */
	static Optional<String> message(RunView view, RunEvent event, java.util.List<RunEvent> context) {
		String run = "Agentic SDLC run " + view.run().id();
		return switch (event.type()) {
			case RUN_CREATED -> Optional.of(run + " started work on this issue.");
			case GATE_OPENED -> Optional.of(run + " is waiting for approval at the " + event.payload().get("gate")
					+ " gate.");
			case ARTIFACT_PRODUCED -> "pull-request".equals(event.payload().get("kind"))
					? Optional.of(run + " opened a pull request: " + event.payload().get("url"))
					: Optional.empty();
			case STATE_CHANGED -> switch (String.valueOf(event.payload().get("to"))) {
				// Reasons can quote model output or internal errors, so tickets only get the gist; the run has the rest.
				case "NEEDS_HUMAN" -> Optional.of(run + " needs a human to continue; see the run for the reason.");
				case "FAILED" -> Optional.of(run + " failed; see the run for the reason.");
				case "CANCELLED" -> Optional.of(run + " was cancelled.");
				case "DONE" -> Optional.of(run + " is complete: the pull request was merged.");
				default -> Optional.empty();
			};
			default -> Optional.empty();
		};
	}

	private String link(UUID runId) {
		return runLinkBase == null || runLinkBase.isBlank() ? null : runLinkBase + runId;
	}
}
