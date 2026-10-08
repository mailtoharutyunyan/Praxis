package io.agenticsdlc.core.intake;

import java.util.UUID;
import reactor.core.publisher.Mono;

/** Remembers, per run, the last event already reported to the ticket, so each update is posted once. */
public interface TicketSyncStore {

	/** 0 if nothing was reported yet. */
	Mono<Long> lastReported(UUID runId);

	Mono<Void> markReported(UUID runId, long seq);
}
