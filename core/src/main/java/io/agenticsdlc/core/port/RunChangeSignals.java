package io.agenticsdlc.core.port;

import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * Hint that a run has new events, possibly written by another instance. Signals may be lost or duplicated;
 * readers treat them only as a reason to re-read the store, which stays the source of truth.
 */
public interface RunChangeSignals {

	/** Hot stream of run ids whose event log grew. */
	Flux<UUID> changes();
}
