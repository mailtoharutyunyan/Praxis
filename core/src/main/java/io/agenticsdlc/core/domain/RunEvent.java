package io.agenticsdlc.core.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only record of something that happened in a run. The same log drives the live UI stream
 * and the audit trail, so events are never updated or deleted.
 *
 * @param seq per-run sequence (1, 2, 3, …) assigned by the store under the run's row lock, so a reader
 *        resuming after {@code seq} never misses an event; null before the event is appended
 * @param actor {@code system}, {@code agent:<role>} or the authenticated user's subject
 * @param payload JSON values only: strings, numbers, booleans, null, lists and maps of those.
 *        Instants and enums are passed as strings so they round-trip unchanged.
 */
public record RunEvent(Long seq, UUID runId, RunEventType type, String actor, Map<String, Object> payload,
		Instant occurredAt) {

	public RunEvent {
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(occurredAt, "occurredAt");
		if (payload == null) {
			payload = Map.of();
		}
		else {
			// Not containsKey(null): immutable maps such as Map.of(...) throw on null lookups.
			if (payload.keySet().stream().anyMatch(Objects::isNull)) {
				throw new IllegalArgumentException("payload keys must not be null");
			}
			// LinkedHashMap keeps insertion order and, unlike Map.copyOf, permits null values (JSON null).
			payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
		}
	}

	public static RunEvent of(UUID runId, RunEventType type, String actor, Map<String, Object> payload, Instant at) {
		return new RunEvent(null, runId, type, actor, payload, at);
	}
}
