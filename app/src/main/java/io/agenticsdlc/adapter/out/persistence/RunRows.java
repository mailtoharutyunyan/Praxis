package io.agenticsdlc.adapter.out.persistence;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.Companion;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.domain.Usage;
import io.r2dbc.spi.Readable;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Row ↔ domain mapping. Column names follow V1__runs_and_events.sql; task columns are prefixed {@code t_}. */
final class RunRows {

	static final String RUN_COLUMNS = """
			r.id, r.task_id, r.state, r.risk, r.gates, r.pending_gate, r.resume_state, r.fix_iterations,
			r.review_loops, r.input_tokens, r.output_tokens, r.cache_read_tokens, r.cache_write_tokens,
			r.cost_micro_usd, r.version, r.created_at, r.updated_at""";

	static final String TASK_COLUMNS = """
			t.id as t_id, t.origin as t_origin, t.external_ref as t_external_ref, t.title as t_title,
			t.description as t_description, t.scm_kind as t_scm_kind, t.clone_url as t_clone_url,
			t.base_branch as t_base_branch, t.trust as t_trust, t.requested_by as t_requested_by,
			t.idempotency_key as t_idempotency_key, t.created_at as t_created_at, t.companions::text as t_companions""";
	private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

	private RunRows() {
	}

	static Run run(Readable row) {
		String[] gates = row.get("gates", String[].class);
		return new Run(
				row.get("id", UUID.class),
				row.get("task_id", UUID.class),
				RunState.valueOf(row.get("state", String.class)),
				enumOrNull(RiskLevel.class, row.get("risk", String.class)),
				gates == null ? null : new GatePolicy(toGates(gates)),
				enumOrNull(Gate.class, row.get("pending_gate", String.class)),
				enumOrNull(RunState.class, row.get("resume_state", String.class)),
				row.get("fix_iterations", Integer.class),
				row.get("review_loops", Integer.class),
				new Usage(row.get("input_tokens", Long.class), row.get("output_tokens", Long.class),
						row.get("cache_read_tokens", Long.class), row.get("cache_write_tokens", Long.class),
						row.get("cost_micro_usd", Long.class)),
				row.get("version", Long.class),
				instant(row.get("created_at", OffsetDateTime.class)),
				instant(row.get("updated_at", OffsetDateTime.class)));
	}

	static Task task(Readable row) {
		return new Task(
				row.get("t_id", UUID.class),
				TaskOrigin.valueOf(row.get("t_origin", String.class)),
				row.get("t_external_ref", String.class),
				row.get("t_title", String.class),
				row.get("t_description", String.class),
				new RepositoryRef(ScmKind.valueOf(row.get("t_scm_kind", String.class)),
						URI.create(row.get("t_clone_url", String.class))),
				row.get("t_base_branch", String.class),
				Trust.valueOf(row.get("t_trust", String.class)),
				row.get("t_requested_by", String.class),
				row.get("t_idempotency_key", String.class),
				instant(row.get("t_created_at", OffsetDateTime.class)),
				companions(row.get("t_companions", String.class)));
	}

	static List<Companion> companions(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		List<Companion> companions = new java.util.ArrayList<>();
		for (tools.jackson.databind.JsonNode node : JSON.readTree(json)) {
			companions.add(new Companion(node.path("alias").asString(), new RepositoryRef(ScmKind.valueOf(node.path("kind")
					.asString()), URI.create(node.path("cloneUrl").asString())), node.path("baseBranch").asString(null)));
		}
		return companions;
	}

	static String companionsJson(List<Companion> companions) {
		List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
		for (Companion c : companions) {
			java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
			item.put("alias", c.alias());
			item.put("kind", c.repository().kind().name());
			item.put("cloneUrl", c.repository().cloneUrl().toString());
			item.put("baseBranch", c.baseBranch());
			list.add(item);
		}
		return JSON.writeValueAsString(list);
	}

	static String[] gates(Run run) {
		return run.gatePolicy() == null ? null
				: run.gatePolicy().gates().stream().map(Enum::name).toArray(String[]::new);
	}

	static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private static EnumSet<Gate> toGates(String[] names) {
		EnumSet<Gate> gates = EnumSet.noneOf(Gate.class);
		Arrays.stream(names).map(Gate::valueOf).forEach(gates::add);
		return gates;
	}

	private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
		return name == null ? null : Enum.valueOf(type, name);
	}

	private static Instant instant(OffsetDateTime value) {
		return value.toInstant();
	}
}
