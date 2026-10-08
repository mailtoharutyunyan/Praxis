package io.agenticsdlc.core.insights;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The aggregation rules behind {@link DeliveryInsights}, as pure functions. */
public final class Insights {

	private Insights() {
	}

	/** DONE over finished runs as a fraction between 0 and 1; null when nothing has finished. */
	public static Double successRate(long done, long finished) {
		return finished == 0 ? null : (double) done / finished;
	}

	/**
	 * The {@code p} percentile (0..1) with linear interpolation between closest ranks, as Postgres
	 * {@code percentile_cont} does; null for no values. The input is not modified.
	 */
	public static Double percentile(List<Double> values, double p) {
		if (p < 0 || p > 1) {
			throw new IllegalArgumentException("percentile must be between 0 and 1: " + p);
		}
		if (values.isEmpty()) {
			return null;
		}
		List<Double> sorted = new ArrayList<>(values);
		sorted.sort(null);
		double rank = p * (sorted.size() - 1);
		int lower = (int) Math.floor(rank);
		int upper = (int) Math.ceil(rank);
		double fraction = rank - lower;
		return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
	}

	public static Double median(List<Double> values) {
		return percentile(values, 0.5);
	}

	/** One entry per date from {@code first} to {@code last} inclusive, 0 where {@code counts} has none. */
	public static List<DailyCount> fillDays(LocalDate first, LocalDate last, List<DailyCount> counts) {
		Map<LocalDate, Long> byDate = new HashMap<>();
		counts.forEach(c -> byDate.merge(c.date(), c.count(), Long::sum));
		List<DailyCount> days = new ArrayList<>();
		for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
			days.add(new DailyCount(day, byDate.getOrDefault(day, 0L)));
		}
		return List.copyOf(days);
	}
}
