package io.agenticsdlc.core.intake;

import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.port.RunStore;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Starts runs from tickets. A trigger (webhook) only names the ticket; the ticket itself is read back from the system
 * so the content comes from the source of truth, not from the (forgeable) webhook body. The ticket must carry the
 * trigger label and belong to a project mapped to a repository. Ticket text is untrusted (origin ≠ PROMPT).
 */
public final class TicketIntake {

	/** Where a ticket project's work goes. */
	public record ProjectTarget(RepositoryRef repository, String baseBranch) {
		public ProjectTarget {
			Objects.requireNonNull(repository, "repository");
		}
	}

	/**
	 * @param eventId unique per trigger (e.g. webhook delivery id or timestamp); repeated deliveries of one trigger
	 *        create one run, a later re-trigger creates a new one
	 * @param actor who caused the trigger in the ticket system
	 */
	public record Trigger(String ticketKey, String eventId, String actor) {
		public Trigger {
			Objects.requireNonNull(ticketKey, "ticketKey");
			Objects.requireNonNull(eventId, "eventId");
			actor = actor == null || actor.isBlank() ? "unknown" : actor;
		}
	}

	public sealed interface Result {
		record Started(RunStore.Submission submission) implements Result {
		}

		record Ignored(String reason) implements Result {
		}
	}

	private final TicketSystem tickets;
	private final TaskIntake intake;
	private final TaskOrigin origin;
	private final String triggerLabel;
	private final Map<String, ProjectTarget> projects;

	public TicketIntake(TicketSystem tickets, TaskIntake intake, TaskOrigin origin, String triggerLabel,
			Map<String, ProjectTarget> projects) {
		this.tickets = Objects.requireNonNull(tickets, "tickets");
		this.intake = Objects.requireNonNull(intake, "intake");
		this.origin = Objects.requireNonNull(origin, "origin");
		this.triggerLabel = Objects.requireNonNull(triggerLabel, "triggerLabel").toLowerCase(Locale.ROOT);
		this.projects = Map.copyOf(projects);
		if (origin == TaskOrigin.PROMPT) {
			throw new IllegalArgumentException("tickets are never a trusted prompt origin");
		}
	}

	public Mono<Result> onTrigger(Trigger trigger) {
		return tickets.fetch(trigger.ticketKey()).flatMap(ticket -> {
			boolean labelled = ticket.labels().stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).equals(triggerLabel));
			if (!labelled) {
				return Mono.just(new Result.Ignored(ticket.key() + " does not carry the label '" + triggerLabel + "'"));
			}
			ProjectTarget target = projects.get(ticket.projectKey());
			if (target == null) {
				return Mono.just(new Result.Ignored("project " + ticket.projectKey() + " is not mapped to a repository"));
			}
			String source = origin.name().toLowerCase(Locale.ROOT);
			String description = (ticket.description().isBlank() ? "(no description)" : ticket.description())
					+ (ticket.url() == null ? "" : "\n\nTicket: " + ticket.url());
			NewTask task = new NewTask(origin, ticket.key(), ticket.title(), description, target.repository(),
					target.baseBranch(), source + ":" + trigger.actor(), source + ":" + ticket.key() + ":" + trigger.eventId());
			return intake.submit(task).map(submission -> (Result) new Result.Started(submission));
		});
	}
}
