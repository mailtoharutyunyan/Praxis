package io.agenticsdlc.core.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsightsTest {

	@Test
	void successRateIsNullWithoutFinishedRuns() {
		assertThat(Insights.successRate(0, 0)).isNull();
	}

	@Test
	void successRateIsDoneOverFinished() {
		assertThat(Insights.successRate(3, 4)).isCloseTo(0.75, within(1e-9));
		assertThat(Insights.successRate(0, 2)).isCloseTo(0.0, within(1e-9));
		assertThat(Insights.successRate(5, 5)).isCloseTo(1.0, within(1e-9));
	}

	@Test
	void percentileOfNothingIsNull() {
		assertThat(Insights.percentile(List.of(), 0.9)).isNull();
		assertThat(Insights.median(List.of())).isNull();
	}

	@Test
	void percentileOfASingleValueIsThatValue() {
		assertThat(Insights.median(List.of(7.0))).isCloseTo(7.0, within(1e-9));
		assertThat(Insights.percentile(List.of(7.0), 0.9)).isCloseTo(7.0, within(1e-9));
	}

	@Test
	void percentileInterpolatesLinearlyLikePercentileCont() {
		List<Double> values = List.of(1.0, 2.0, 3.0, 4.0);
		assertThat(Insights.median(values)).isCloseTo(2.5, within(1e-9));
		assertThat(Insights.percentile(values, 0.5)).isCloseTo(2.5, within(1e-9));
		assertThat(Insights.percentile(values, 0.9)).isCloseTo(3.7, within(1e-9));
		assertThat(Insights.percentile(values, 0.0)).isCloseTo(1.0, within(1e-9));
		assertThat(Insights.percentile(values, 1.0)).isCloseTo(4.0, within(1e-9));
		assertThat(Insights.median(List.of(1.0, 2.0, 10.0))).isCloseTo(2.0, within(1e-9));
	}

	@Test
	void percentileSortsUnsortedInputWithoutMutatingIt() {
		List<Double> values = new ArrayList<>(List.of(4.0, 1.0, 3.0, 2.0));
		assertThat(Insights.median(values)).isCloseTo(2.5, within(1e-9));
		assertThat(Insights.percentile(values, 0.9)).isCloseTo(3.7, within(1e-9));
		assertThat(values).containsExactly(4.0, 1.0, 3.0, 2.0);
	}

	@Test
	void fillDaysFillsGapsWithZeroInOrder() {
		LocalDate first = LocalDate.of(2026, 10, 1);
		LocalDate last = LocalDate.of(2026, 10, 5);
		List<DailyCount> filled = Insights.fillDays(first, last,
				List.of(new DailyCount(LocalDate.of(2026, 10, 4), 2), new DailyCount(LocalDate.of(2026, 10, 2), 5)));

		assertThat(filled).containsExactly(
				new DailyCount(LocalDate.of(2026, 10, 1), 0),
				new DailyCount(LocalDate.of(2026, 10, 2), 5),
				new DailyCount(LocalDate.of(2026, 10, 3), 0),
				new DailyCount(LocalDate.of(2026, 10, 4), 2),
				new DailyCount(LocalDate.of(2026, 10, 5), 0));
	}

	@Test
	void fillDaysHasExactLengthAndIgnoresCountsOutsideTheRange() {
		LocalDate first = LocalDate.of(2026, 9, 29);
		LocalDate last = LocalDate.of(2026, 10, 1);
		List<DailyCount> filled = Insights.fillDays(first, last, List.of(
				new DailyCount(LocalDate.of(2026, 9, 28), 9),
				new DailyCount(LocalDate.of(2026, 9, 30), 1),
				new DailyCount(LocalDate.of(2026, 10, 2), 9)));

		assertThat(filled).containsExactly(
				new DailyCount(LocalDate.of(2026, 9, 29), 0),
				new DailyCount(LocalDate.of(2026, 9, 30), 1),
				new DailyCount(LocalDate.of(2026, 10, 1), 0));
	}

	@Test
	void fillDaysOfASingleDay() {
		LocalDate day = LocalDate.of(2026, 10, 8);
		assertThat(Insights.fillDays(day, day, List.of())).containsExactly(new DailyCount(day, 0));
	}
}
