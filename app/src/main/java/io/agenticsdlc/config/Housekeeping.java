package io.agenticsdlc.config;

import java.time.Instant;
import reactor.core.publisher.Mono;

/** Deletes data past its retention (see {@link AgenticProperties.Retention}). */
public interface Housekeeping {

	/**
	 * Deletes up to {@code batch} finished runs last updated before {@code cutoff}, with their events and sync
	 * cursors, and tasks left without runs. Facts learned in those runs stay, without the link to the run.
	 *
	 * @return how many runs were deleted
	 */
	Mono<Long> deleteFinishedRuns(Instant cutoff, int batch);

	/** Deletes API tokens revoked or expired before {@code cutoff}; emits how many. */
	Mono<Long> deleteDeadTokens(Instant cutoff);
}
