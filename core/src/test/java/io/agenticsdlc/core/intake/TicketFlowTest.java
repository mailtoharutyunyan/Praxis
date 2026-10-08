package io.agenticsdlc.core.intake;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static io.agenticsdlc.core.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TicketFlowTest {

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final Map<String, Ticket> tickets = new HashMap<>();
	private final List<String> comments = new ArrayList<>();
	private final TicketSystem jira = new TicketSystem() {
		@Override
		public Mono<Ticket> fetch(String key) {
			Ticket ticket = tickets.get(key);
			return ticket == null ? Mono.error(new IllegalArgumentException("no ticket " + key)) : Mono.just(ticket);
		}

		@Override
		public Mono<Void> comment(String key, String text, String link) {
			comments.add(key + " | " + text + (link == null ? "" : " | " + link));
			return Mono.empty();
		}
	};
	private final Map<UUID, Long> cursors = new HashMap<>();
	private final TicketSyncStore sync = new TicketSyncStore() {
		@Override
		public Mono<Long> lastReported(UUID runId) {
			return Mono.just(cursors.getOrDefault(runId, 0L));
		}

		@Override
		public Mono<Void> markReported(UUID runId, long seq) {
			cursors.put(runId, seq);
			return Mono.empty();
		}
	};
	private final TicketIntake intake = new TicketIntake(jira,
			new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES), TaskOrigin.JIRA, "Agentic",
			Map.of("SHOP", new TicketIntake.ProjectTarget(Fixtures.REPO, "develop")));

	private void ticket(String key, String project, Set<String> labels) {
		tickets.put(key, new Ticket(key, project, "Add search", "Users want search.", labels,
				"https://acme.atlassian.net/browse/" + key));
	}

	@Test
	void labelledTicketStartsAnUntrustedRunOncePerTrigger() {
		ticket("SHOP-1", "SHOP", Set.of("agentic", "backend"));
		TicketIntake.Result result = intake.onTrigger(new TicketIntake.Trigger("SHOP-1", "evt-1", "acc-42")).block();

		assertThat(result).isInstanceOfSatisfying(TicketIntake.Result.Started.class, started -> {
			var task = started.submission().view().task();
			assertThat(task.origin()).isEqualTo(TaskOrigin.JIRA);
			assertThat(task.trust()).isEqualTo(Trust.UNTRUSTED);
			assertThat(task.externalRef()).isEqualTo("SHOP-1");
			assertThat(task.baseBranch()).isEqualTo("develop");
			assertThat(task.requestedBy()).isEqualTo("jira:acc-42");
			assertThat(task.description()).contains("Users want search.", "Ticket: https://acme.atlassian.net/browse/SHOP-1");
		});
		var again = intake.onTrigger(new TicketIntake.Trigger("SHOP-1", "evt-1", "acc-42")).block();
		assertThat(((TicketIntake.Result.Started) again).submission().created()).isFalse();
		var retrigger = intake.onTrigger(new TicketIntake.Trigger("SHOP-1", "evt-2", "acc-42")).block();
		assertThat(((TicketIntake.Result.Started) retrigger).submission().created()).isTrue();
	}

	@Test
	void unlabelledOrUnmappedTicketsAreIgnored() {
		ticket("SHOP-2", "SHOP", Set.of("backend"));
		ticket("OPS-1", "OPS", Set.of("agentic"));
		assertThat(intake.onTrigger(new TicketIntake.Trigger("SHOP-2", "e", "a")).block())
				.isInstanceOfSatisfying(TicketIntake.Result.Ignored.class, i -> assertThat(i.reason()).contains("label"));
		assertThat(intake.onTrigger(new TicketIntake.Trigger("OPS-1", "e", "a")).block())
				.isInstanceOfSatisfying(TicketIntake.Result.Ignored.class, i -> assertThat(i.reason()).contains("not mapped"));
		assertThatThrownBy(() -> new TicketIntake(jira, null, TaskOrigin.PROMPT, "x", Map.of()))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void notableEventsAreCommentedExactlyOnce() {
		ticket("SHOP-3", "SHOP", Set.of("agentic"));
		UUID runId = ((TicketIntake.Result.Started) intake.onTrigger(new TicketIntake.Trigger("SHOP-3", "e", "a")).block())
				.submission().view().run().id();
		TicketUpdates updates = new TicketUpdates(store, jira, sync, TaskOrigin.JIRA, java.time.Clock.offset(CLOCK,
				Duration.ofSeconds(1)), Duration.ofHours(1), "https://agentic.example.com/runs/");

		assertThat(updates.sweep().collectList().block()).containsExactly(1);
		assertThat(comments).containsExactly("SHOP-3 | Agentic SDLC run " + runId
				+ " started work on this issue. | https://agentic.example.com/runs/" + runId);

		Run run = store.run(runId);
		Run escalated = run.transitionTo(RunState.TRIAGING, T0.plusMillis(1)).escalate(T0.plusMillis(1));
		store.update(run, escalated, List.of(
				RunEvent.of(runId, RunEventType.ERROR, "system", Map.of("kind", "ESCALATED", "reason", "no API key"), T0),
				RunEvent.of(runId, RunEventType.STATE_CHANGED, "system", Map.of("from", "TRIAGING", "to", "NEEDS_HUMAN"), T0)))
				.block();
		updates.sweep().blockLast();
		assertThat(comments).hasSize(2).last().asString().contains("needs a human: no API key");
		updates.sweep().blockLast();
		assertThat(comments).hasSize(2);
	}

	@Test
	void promptRunsAreNotReported() {
		new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES).submit(Fixtures.prompt("alice")).block();
		TicketUpdates updates = new TicketUpdates(store, jira, sync, TaskOrigin.JIRA, java.time.Clock.offset(CLOCK,
				Duration.ofSeconds(1)), Duration.ofHours(1), null);
		assertThat(updates.sweep().collectList().block()).isEmpty();
		assertThat(comments).isEmpty();
	}
}
