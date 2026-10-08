package io.agenticsdlc.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.agenticsdlc.TestcontainersConfigurationAccess;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.insights.DailyCount;
import io.agenticsdlc.core.insights.InsightsStore;
import io.agenticsdlc.core.port.RunStore;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;

@Import(TestcontainersConfigurationAccess.class)
@SpringBootTest(properties = { "agentic.worker.enabled=false", "agentic.sandbox.enabled=false" })
class R2dbcInsightsStoreTest {

	private static final RepositoryRef REPO = new RepositoryRef(ScmKind.GITHUB,
			URI.create("https://github.com/acme/shop.git"));

	@Autowired
	InsightsStore insights;

	@Autowired
	RunStore store;

	@Autowired
	DatabaseClient db;

	/** A three-day window long ago that no other test writes into. */
	private LocalDate firstDay;
	private Instant from;
	private Instant to;

	@BeforeEach
	void window() {
		firstDay = LocalDate.of(1990, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 3000) * 4L);
		from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
		to = from.plus(Duration.ofDays(3));
	}

	private Run run(Instant createdAt, String state, long input, long output, long cacheRead, long cacheWrite, long cost) {
		Task task = new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "Insights", "d", REPO, null, Trust.TRUSTED,
				"insights", null, createdAt);
		Run run = Run.start(UUID.randomUUID(), task.id(), createdAt);
		store.submit(task, run, List.of(RunEvent.of(run.id(), RunEventType.RUN_CREATED, "insights", Map.of(), createdAt)))
				.block();
		db.sql("""
				update runs set state = :state, input_tokens = :input, output_tokens = :output,
				    cache_read_tokens = :cacheRead, cache_write_tokens = :cacheWrite, cost_micro_usd = :cost
				where id = :id""")
				.bind("state", state).bind("input", input).bind("output", output).bind("cacheRead", cacheRead)
				.bind("cacheWrite", cacheWrite).bind("cost", cost).bind("id", run.id()).then().block();
		return store.find(run.id()).block().run();
	}

	private Run run(Instant createdAt, String state) {
		return run(createdAt, state, 0, 0, 0, 0, 0);
	}

	private void events(Run run, RunEvent... events) {
		store.update(run, run, List.of(events)).block();
	}

	private RunEvent stateChanged(Run run, String fromState, String toState, Instant at) {
		return RunEvent.of(run.id(), RunEventType.STATE_CHANGED, "system", Map.of("from", fromState, "to", toState), at);
	}

	@Test
	void totalsCountRunsByOutcomeAndSumUsageInsideTheWindow() {
		run(from.plus(Duration.ofHours(1)), "DONE", 10, 20, 30, 40, 1_000_000);
		run(from.plus(Duration.ofHours(26)), "FAILED", 1, 2, 3, 4, 500_000);
		run(from.plus(Duration.ofHours(27)), "CANCELLED");
		run(from.plus(Duration.ofHours(28)), "DONE");
		run(from.plus(Duration.ofHours(29)), "IMPLEMENTING", 5, 0, 0, 0, 7);
		// Outside the window: just before it starts, and exactly at its (exclusive) end.
		run(from.minusSeconds(1), "DONE", 1000, 0, 0, 0, 9_000_000);
		run(to, "FAILED", 1000, 0, 0, 0, 9_000_000);

		InsightsStore.Totals totals = insights.totals(from, to).block();

		assertThat(totals).isEqualTo(new InsightsStore.Totals(5, 2, 1, 1, 1_500_007, 115));
	}

	@Test
	void anEmptyWindowHasZeroTotalsAndNoFigures() {
		assertThat(insights.totals(from, to).block()).isEqualTo(new InsightsStore.Totals(0, 0, 0, 0, 0, 0));
		assertThat(insights.minutesToPullRequest(from, to).collectList().block()).isEmpty();
		assertThat(insights.runsPerDay(from, to).collectList().block()).isEmpty();
	}

	@Test
	void minutesToTheFirstPullRequestAreCountedOncePerRun() {
		Instant created = from.plus(Duration.ofHours(1));
		Run reopened = run(created, "PR_OPEN");
		events(reopened,
				stateChanged(reopened, "RECEIVED", "TRIAGING", created.plus(Duration.ofMinutes(1))),
				stateChanged(reopened, "PUBLISHING", "PR_OPEN", created.plus(Duration.ofMinutes(90))),
				stateChanged(reopened, "PR_OPEN", "IMPLEMENTING", created.plus(Duration.ofMinutes(120))),
				stateChanged(reopened, "PUBLISHING", "PR_OPEN", created.plus(Duration.ofMinutes(300))));

		Instant otherCreated = from.plus(Duration.ofHours(30));
		Run quick = run(otherCreated, "DONE");
		events(quick, stateChanged(quick, "PUBLISHING", "PR_OPEN", otherCreated.plus(Duration.ofSeconds(30 * 60 + 30))));

		Run noPullRequest = run(from.plus(Duration.ofHours(2)), "FAILED");
		events(noPullRequest, stateChanged(noPullRequest, "RECEIVED", "TRIAGING", from.plus(Duration.ofHours(3))));

		Instant outsideCreated = from.minus(Duration.ofHours(1));
		Run outside = run(outsideCreated, "DONE");
		events(outside, stateChanged(outside, "PUBLISHING", "PR_OPEN", from.plus(Duration.ofMinutes(10))));

		List<Double> minutes = insights.minutesToPullRequest(from, to).collectList().block();

		assertThat(minutes).hasSize(2);
		assertThat(minutes.stream().sorted().toList()).satisfiesExactly(
				m -> assertThat(m).isCloseTo(30.5, within(1e-6)),
				m -> assertThat(m).isCloseTo(90.0, within(1e-6)));
	}

	@Test
	void runsPerDayGroupsByUtcDateOldestFirstAndSkipsEmptyDays() {
		run(from.plus(Duration.ofMinutes(1)), "DONE");
		run(from.plus(Duration.ofHours(23).plusMinutes(59)), "FAILED");
		run(from.plus(Duration.ofDays(2)), "RECEIVED");
		run(from.plus(Duration.ofDays(2).plusHours(12)), "RECEIVED");
		run(from.plus(Duration.ofDays(2).plusHours(23)), "RECEIVED");
		run(from.minusSeconds(1), "DONE");
		run(to, "DONE");

		assertThat(insights.runsPerDay(from, to).collectList().block()).containsExactly(
				new DailyCount(firstDay, 2),
				new DailyCount(firstDay.plusDays(2), 3));
	}
}
