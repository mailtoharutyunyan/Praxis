package io.agenticsdlc.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** All application settings under {@code agentic.*}. Validated at startup: a bad value fails fast. */
@Validated
@ConfigurationProperties("agentic")
public record AgenticProperties(@Valid @NotNull Worker worker, @Valid @NotNull Limits limits,
		@Valid @NotNull Gates gates, @Valid @NotNull Security security, @Valid @NotNull Events events,
		@Valid @NotNull StubStages stubStages) {

	/**
	 * @param enabled run the background worker on this instance (disable for API-only replicas)
	 * @param concurrency runs processed in parallel by this instance
	 * @param pollInterval idle wait before looking for claimable runs again
	 * @param lease how long a claimed run stays reserved without a heartbeat
	 */
	public record Worker(@DefaultValue("true") boolean enabled, @DefaultValue("4") @Min(1) @Max(64) int concurrency,
			@DefaultValue("1s") @NotNull Duration pollInterval, @DefaultValue("60s") @NotNull Duration lease) {
	}

	public record Limits(@DefaultValue("3") @Min(0) int maxFixIterations, @DefaultValue("2") @Min(0) int maxReviewLoops,
			@DefaultValue("2000000") @Min(1) long maxTokens,
			@DefaultValue("10.00") @NotNull @DecimalMin("0.01") BigDecimal maxCostUsd,
			@DefaultValue("30m") @NotNull Duration stageTimeout) {

		public long maxCostMicroUsd() {
			return maxCostUsd.movePointRight(6).longValueExact();
		}
	}

	/** @param forbidSelfApproval four-eyes rule: a task's requester may not decide its gates */
	public record Gates(@DefaultValue("false") boolean forbidSelfApproval) {
	}

	/**
	 * @param rolesClaim JWT claim holding the user's roles; dotted paths reach nested claims
	 *        (Keycloak: {@code realm_access.roles})
	 * @param corsAllowedOrigins browser origins allowed to call the API (the future web UI)
	 */
	public record Security(@DefaultValue("roles") @NotBlank String rolesClaim,
			@DefaultValue({}) List<String> corsAllowedOrigins) {
	}

	/**
	 * @param fallbackPoll live streams re-read the log at least this often, covering lost notifications
	 * @param keepAlive SSE comment interval that keeps proxies from closing idle streams
	 */
	public record Events(@DefaultValue("5s") @NotNull Duration fallbackPoll,
			@DefaultValue("15s") @NotNull Duration keepAlive) {
	}

	/** Placeholder stage handlers until the real agent stages exist. Never enable in production. */
	public record StubStages(@DefaultValue("false") boolean enabled) {
	}
}
