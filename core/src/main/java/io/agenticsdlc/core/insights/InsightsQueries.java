package io.agenticsdlc.core.insights;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Delivery figures for the last days: the window is today and the {@code days - 1} UTC dates before it. */
public final class InsightsQueries {

	public static final int MAX_DAYS = 365;

	private final InsightsStore store;
	private final Clock clock;

	public InsightsQueries(InsightsStore store, Clock clock) {
		this.store = Objects.requireNonNull(store, "store");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/** @throws IllegalArgumentException if {@code days} is outside 1..{@value #MAX_DAYS} */
	public Mono<DeliveryInsights> lastDays(int days) {
		if (days < 1 || days > MAX_DAYS) {
			throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS + ": " + days);
		}
		Instant to = clock.instant();
		LocalDate last = LocalDate.ofInstant(to, ZoneOffset.UTC);
		LocalDate first = last.minusDays(days - 1L);
		Instant from = first.atStartOfDay(ZoneOffset.UTC).toInstant();
		return Mono.zip(store.totals(from, to), store.minutesToPullRequest(from, to).collectList(),
				store.runsPerDay(from, to).collectList()).map(figures -> {
					InsightsStore.Totals totals = figures.getT1();
					List<Double> minutes = figures.getT2();
					long finished = totals.done() + totals.failed() + totals.cancelled();
					return new DeliveryInsights(days, from, to, totals.started(), totals.done(), totals.failed(),
							totals.cancelled(), Insights.successRate(totals.done(), finished),
							Insights.median(minutes), Insights.percentile(minutes, 0.9), minutes.size(),
							totals.costMicroUsd(), totals.totalTokens(), Insights.fillDays(first, last, figures.getT3()));
				});
	}
}
