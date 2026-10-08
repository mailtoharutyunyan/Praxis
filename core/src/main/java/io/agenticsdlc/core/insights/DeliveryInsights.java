package io.agenticsdlc.core.insights;

import java.time.Instant;
import java.util.List;

/**
 * Delivery figures for the runs created in {@code [from, to)}.
 *
 * @param successRate DONE over finished runs, null when none finished
 * @param medianMinutesToPullRequest null when no run opened a pull request
 * @param p90MinutesToPullRequest null when no run opened a pull request
 * @param pullRequests how many runs the time-to-pull-request figures are based on
 * @param runsPerDay one entry per UTC date of the window, oldest first
 */
public record DeliveryInsights(int days, Instant from, Instant to, long runsStarted, long done, long failed,
		long cancelled, Double successRate, Double medianMinutesToPullRequest, Double p90MinutesToPullRequest,
		long pullRequests, long costMicroUsd, long totalTokens, List<DailyCount> runsPerDay) {

	public DeliveryInsights {
		runsPerDay = List.copyOf(runsPerDay);
	}
}
