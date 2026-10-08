package io.agenticsdlc.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunEventTest {

	private final UUID runId = UUID.randomUUID();

	@ParameterizedTest
	@EnumSource(RunEventType.class)
	void newEventsHaveNoIdUntilStored(RunEventType type) {
		RunEvent event = RunEvent.of(runId, type, "system", Map.of("k", "v"), Instant.EPOCH);
		assertThat(event.seq()).isNull();
		assertThat(event.type()).isEqualTo(type);
	}

	@Test
	void payloadIsDefensivelyCopied() {
		Map<String, Object> payload = new HashMap<>(Map.of("state", "TRIAGING"));
		RunEvent event = RunEvent.of(runId, RunEventType.STATE_CHANGED, "system", payload, Instant.EPOCH);
		payload.put("state", "FAILED");
		assertThat(event.payload()).containsEntry("state", "TRIAGING");
	}

	@Test
	void payloadAllowsJsonNullValuesButNotNullKeys() {
		Map<String, Object> withNullValue = new HashMap<>();
		withNullValue.put("exitCode", null);
		assertThat(RunEvent.of(runId, RunEventType.COMMAND_OUTPUT, "system", withNullValue, Instant.EPOCH).payload())
				.containsEntry("exitCode", null);

		Map<String, Object> withNullKey = new HashMap<>();
		withNullKey.put(null, "x");
		assertThatThrownBy(() -> RunEvent.of(runId, RunEventType.ERROR, "system", withNullKey, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nullPayloadBecomesEmpty() {
		assertThat(RunEvent.of(runId, RunEventType.ERROR, "system", null, Instant.EPOCH).payload()).isEmpty();
	}

	@Test
	void requiresActor() {
		assertThatThrownBy(() -> RunEvent.of(runId, RunEventType.ERROR, null, Map.of(), Instant.EPOCH))
				.isInstanceOf(NullPointerException.class);
	}
}
