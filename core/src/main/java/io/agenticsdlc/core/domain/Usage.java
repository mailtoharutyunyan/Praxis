package io.agenticsdlc.core.domain;

/**
 * Accumulated model usage for a run. Cache reads and writes are billed differently from plain input,
 * so they are tracked separately. Cost is in micro-USD to keep arithmetic exact.
 */
public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
		long costMicroUsd) {

	public static final Usage ZERO = new Usage(0, 0, 0, 0, 0);

	public Usage {
		if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0 || costMicroUsd < 0) {
			throw new IllegalArgumentException("usage must not be negative");
		}
	}

	public Usage plus(Usage other) {
		return new Usage(
				Math.addExact(inputTokens, other.inputTokens),
				Math.addExact(outputTokens, other.outputTokens),
				Math.addExact(cacheReadTokens, other.cacheReadTokens),
				Math.addExact(cacheWriteTokens, other.cacheWriteTokens),
				Math.addExact(costMicroUsd, other.costMicroUsd));
	}

	public long totalTokens() {
		return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
	}
}
