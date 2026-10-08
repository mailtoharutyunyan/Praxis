package io.agenticsdlc.adapter.out.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON encoding of {@code run_events.payload} with Jackson 3. Strings are stripped of U+0000 because Postgres
 * rejects it in jsonb (command output from builds and tests can contain it).
 */
@Component
class EventPayloadCodec {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JsonMapper mapper;

	EventPayloadCodec(JsonMapper mapper) {
		this.mapper = mapper;
	}

	String encode(Map<String, Object> payload) {
		return mapper.writeValueAsString(sanitize(payload));
	}

	Map<String, Object> decode(String json) {
		return json == null || json.isEmpty() ? Map.of() : mapper.readValue(json, MAP);
	}

	static String stripNul(String s) {
		return s.indexOf('\u0000') < 0 ? s : s.replace("\u0000", "");
	}

	static Object sanitize(Object value) {
		return switch (value) {
			case String s -> stripNul(s);
			case Map<?, ?> m -> {
				Map<String, Object> copy = new LinkedHashMap<>();
				m.forEach((k, v) -> copy.put(String.valueOf(k), sanitize(v)));
				yield copy;
			}
			case Collection<?> c -> {
				List<Object> copy = new ArrayList<>(c.size());
				c.forEach(v -> copy.add(sanitize(v)));
				yield copy;
			}
			case null -> null;
			default -> value;
		};
	}
}
