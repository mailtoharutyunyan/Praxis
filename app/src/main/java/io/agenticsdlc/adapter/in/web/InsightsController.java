package io.agenticsdlc.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agenticsdlc.core.insights.DeliveryInsights;
import io.agenticsdlc.core.insights.InsightsQueries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Delivery insights: how the agents did over the last days. */
@RestController
@RequestMapping("/api/v1/insights")
class InsightsController {

	private final InsightsQueries insights;

	InsightsController(InsightsQueries insights) {
		this.insights = insights;
	}

	/** Named like the run states, as the API reports them. */
	record FinishedResponse(@JsonProperty("DONE") long done, @JsonProperty("FAILED") long failed,
			@JsonProperty("CANCELLED") long cancelled) {
	}

	record MinutesResponse(Double median, Double p90, long count) {
	}

	record DayResponse(LocalDate date, long count) {
	}

	record InsightsResponse(int days, Instant from, Instant to, long runsStarted, FinishedResponse finished,
			Double successRate, MinutesResponse minutesToPullRequest, double costUsd, long totalTokens,
			List<DayResponse> runsPerDay) {
		static InsightsResponse of(DeliveryInsights i) {
			return new InsightsResponse(i.days(), i.from(), i.to(), i.runsStarted(),
					new FinishedResponse(i.done(), i.failed(), i.cancelled()), i.successRate(),
					new MinutesResponse(i.medianMinutesToPullRequest(), i.p90MinutesToPullRequest(), i.pullRequests()),
					i.costMicroUsd() / 1_000_000.0, i.totalTokens(),
					i.runsPerDay().stream().map(d -> new DayResponse(d.date(), d.count())).toList());
		}
	}

	/** @param days the window: today and the {@code days - 1} UTC dates before it */
	@GetMapping
	Mono<InsightsResponse> get(@RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
		return insights.lastDays(days).map(InsightsResponse::of);
	}
}
