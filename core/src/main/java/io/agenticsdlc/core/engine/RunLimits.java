package io.agenticsdlc.core.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * Guard rails for every run. Hitting a loop limit or the budget hands the run to a human ({@code NEEDS_HUMAN})
 * instead of letting an agent spin or spend without bound.
 *
 * @param maxFixIterations implement ⇄ verify loops before escalation
 * @param maxReviewLoops review → implement loops before escalation
 * @param maxTokens total model tokens per run, including cache reads and writes
 * @param maxCostMicroUsd model cost per run in micro-USD
 * @param stageTimeout longest a single stage may run before it is treated as stuck
 */
public record RunLimits(int maxFixIterations, int maxReviewLoops, long maxTokens, long maxCostMicroUsd,
		Duration stageTimeout) {

	public RunLimits {
		Objects.requireNonNull(stageTimeout, "stageTimeout");
		if (maxFixIterations < 0 || maxReviewLoops < 0 || maxTokens <= 0 || maxCostMicroUsd <= 0
				|| stageTimeout.isNegative() || stageTimeout.isZero()) {
			throw new IllegalArgumentException("run limits must be positive");
		}
	}
}
