package io.agenticsdlc.core.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class InsightsQueriesTest {

	private static final Instant NOW = Instant.parse("2026-10-08T15:30:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	/** Fixed figures; records every window it is asked about. */
	static final class FakeStore implements InsightsStore {
		final List<Instant[]> windows = new ArrayList<>();
		InsightsStore.Totals totals = new InsightsStore.Totals(10, 3, 1, 0, 1_500_000, 4242);
		List<Double> minutes = List.of(4.0, 1.0, 3.0, 2.0);
		List<DailyCount> days = List.of(new DailyCount(LocalDate.of(2026, 10, 3), 4),
				new DailyCount(LocalDate.of(2026, 10, 8), 6));

		@Override
		public Mono<Totals> totals(Instant from, Instant to) {
			windows.add(new Instant[] { from, to });
			return Mono.just(totals);
		}

		@Override
		public Flux<Double> minutesToPullRequest(Instant from, Instant to) {
			windows.add(new Instant[] { from, to });
			return Flux.fromIterable(minutes);
		}

		@Override
		public Flux<DailyCount> runsPerDay(Instant from, Instant to) {
			windows.add(new Instant[] { from, to });
			return Flux.fromIterable(days);
		}
	}

	@Test
	void windowStartsAtMidnightUtcDaysMinusOneAgoAndEndsNow() {
		FakeStore store = new FakeStore();
		DeliveryInsights insights = new InsightsQueries(store, CLOCK).lastDays(7).block();

		assertThat(store.windows).hasSize(3).allSatisfy(w -> {
			assertThat(w[0]).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
			assertThat(w[1]).isEqualTo(NOW);
		});
		assertThat(insights.days()).isEqualTo(7);
		assertThat(insights.from()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
		assertThat(insights.to()).isEqualTo(NOW);
		assertThat(insights.runsPerDay()).hasSize(7);
		assertThat(insights.runsPerDay().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 2));
		assertThat(insights.runsPerDay().getLast().date()).isEqualTo(LocalDate.of(2026, 10, 8));
	}

	@Test
	void oneDayCoversOnlyToday() {
		FakeStore store = new FakeStore();
		DeliveryInsights insights = new InsightsQueries(store, CLOCK).lastDays(1).block();

		assertThat(store.windows.getFirst()[0]).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
		assertThat(insights.runsPerDay()).containsExactly(new DailyCount(LocalDate.of(2026, 10, 8), 6));
	}

	@Test
	void daysOutsideOneTo365AreRejected() {
		InsightsQueries queries = new InsightsQueries(new FakeStore(), CLOCK);
		assertThatThrownBy(() -> queries.lastDays(0).block()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> queries.lastDays(366).block()).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> queries.lastDays(-5).block()).isInstanceOf(IllegalArgumentException.class);
		assertThat(queries.lastDays(365).block().runsPerDay()).hasSize(365);
	}

	@Test
	void combinesTheStoreFigures() {
		FakeStore store = new FakeStore();
		DeliveryInsights insights = new InsightsQueries(store, CLOCK).lastDays(7).block();

		assertThat(insights.runsStarted()).isEqualTo(10);
		assertThat(insights.successRate()).isCloseTo(0.75, within(1e-9));
		assertThat(insights.costMicroUsd()).isEqualTo(1_500_000);
		assertThat(insights.totalTokens()).isEqualTo(4242);
		assertThat(insights.runsPerDay()).containsExactly(
				new DailyCount(LocalDate.of(2026, 10, 2), 0),
				new DailyCount(LocalDate.of(2026, 10, 3), 4),
				new DailyCount(LocalDate.of(2026, 10, 4), 0),
				new DailyCount(LocalDate.of(2026, 10, 5), 0),
				new DailyCount(LocalDate.of(2026, 10, 6), 0),
				new DailyCount(LocalDate.of(2026, 10, 7), 0),
				new DailyCount(LocalDate.of(2026, 10, 8), 6));
	}

	@Test
	void noFinishedRunsAndNoPullRequestsGiveNullRate() {
		FakeStore store = new FakeStore();
		store.totals = new InsightsStore.Totals(2, 0, 0, 0, 0, 0);
		store.minutes = List.of();
		store.days = List.of();
		DeliveryInsights insights = new InsightsQueries(store, CLOCK).lastDays(30).block();

		assertThat(insights.runsStarted()).isEqualTo(2);
		assertThat(insights.successRate()).isNull();
		assertThat(insights.runsPerDay()).hasSize(30).allSatisfy(d -> assertThat(d.count()).isZero());
	}
}
