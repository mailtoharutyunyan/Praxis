package io.agenticsdlc.core.insights;

import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Raw delivery figures over the runs created in {@code [from, to)}, aggregated by the store. */
public interface InsightsStore {

	/** Run counts (all, and by finished state) and summed usage. */
	Mono<Totals> totals(Instant from, Instant to);

	/** Per run that opened a pull request: minutes from its creation to its first {@code PR_OPEN}. */
	Flux<Double> minutesToPullRequest(Instant from, Instant to);

	/** Runs created per UTC date, oldest first; days without runs are left out. */
	Flux<DailyCount> runsPerDay(Instant from, Instant to);

	/** @param totalTokens input, output, cache-read and cache-write tokens together */
	record Totals(long started, long done, long failed, long cancelled, long costMicroUsd, long totalTokens) {
	}
}
