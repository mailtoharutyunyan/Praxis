package io.agenticsdlc.core.engine;

import io.agenticsdlc.core.domain.RunState;
import reactor.core.publisher.Mono;

/**
 * Does the work of one working stage (triage, context, spec, implement, verify, review, publish).
 * Implementations live in adapters and must be idempotent: a stage can run again after a lease expiry.
 */
public interface StageHandler {

	RunState stage();

	Mono<StageOutcome> execute(StageContext context);
}
