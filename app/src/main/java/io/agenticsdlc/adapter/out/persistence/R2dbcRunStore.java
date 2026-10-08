package io.agenticsdlc.adapter.out.persistence;

import static io.agenticsdlc.adapter.out.persistence.RunRows.name;
import static io.agenticsdlc.adapter.out.persistence.RunRows.timestamp;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.NodeIdentity;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.port.ConcurrentRunUpdateException;
import io.agenticsdlc.core.port.LeaseLostException;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.port.RunStore.Cursor;
import io.r2dbc.postgresql.codec.Json;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Postgres implementation of {@link RunStore} (ADR-0002). Every state change and its events commit in one
 * transaction; event sequence numbers are reserved by incrementing {@code runs.last_event_seq} under the row lock,
 * so they are gap-free and in commit order. Each commit with events sends a {@code NOTIFY run_events} with the run
 * id, delivered by Postgres only after the transaction commits.
 * <p>
 * Claims respect workspace affinity: a run is claimed by the node that holds its working copy
 * ({@code runs.workspace_node}), or by any node once that one has stopped sending heartbeats.
 */
@Repository
class R2dbcRunStore implements RunStore {

	static final String CHANNEL = "run_events";

	/** Must equal {@code RunState.working()} and the {@code runs_claimable_idx} predicate (SchemaContractTest). */
	private static final String WORKING_STATES = RunState.working().stream()
			.map(s -> "'" + s.name() + "'")
			.collect(Collectors.joining(", ", "(", ")"));

	private final DatabaseClient db;
	private final TransactionalOperator tx;
	private final EventPayloadCodec codec;
	private final String node;
	private final Duration nodeTimeout;

	R2dbcRunStore(DatabaseClient db, TransactionalOperator tx, EventPayloadCodec codec, NodeIdentity node,
			AgenticProperties properties) {
		this.db = db;
		this.tx = tx;
		this.codec = codec;
		this.node = node.node();
		this.nodeTimeout = properties.worker().nodeTimeout();
	}

	@Override
	public Mono<Submission> submit(Task task, Run run, List<RunEvent> events) {
		Mono<Submission> insert = insertTask(task)
				.then(insertRun(run, events.size()))
				.then(insertEvents(run.id(), 1, events))
				.then(notifyChange(run.id(), events.size()))
				.thenReturn(new Submission(new RunView(run, task), true));
		Mono<Submission> attempt = task.idempotencyKey() == null ? insert
				: existing(task).map(view -> new Submission(view, false)).switchIfEmpty(insert);
		return tx.transactional(attempt)
				// Two concurrent submissions with the same key: the loser hits the unique index; return the winner's.
				.onErrorResume(DataIntegrityViolationException.class,
						e -> task.idempotencyKey() == null ? Mono.error(e)
								: existing(task).map(view -> new Submission(view, false))
										.switchIfEmpty(Mono.error(e)));
	}

	@Override
	public Mono<RunView> find(UUID runId) {
		return db.sql("select " + RunRows.RUN_COLUMNS + ", " + RunRows.TASK_COLUMNS
						+ " from runs r join tasks t on t.id = r.task_id where r.id = :id")
				.bind("id", runId)
				.map(row -> new RunView(RunRows.run(row), RunRows.task(row)))
				.one();
	}

	@Override
	public Flux<RunView> list(Set<RunState> states, Cursor before, int limit) {
		StringBuilder sql = new StringBuilder("select " + RunRows.RUN_COLUMNS + ", " + RunRows.TASK_COLUMNS
				+ " from runs r join tasks t on t.id = r.task_id where true");
		if (!states.isEmpty()) {
			sql.append(" and r.state = any(:states)");
		}
		if (before != null) {
			sql.append(before.id() == null ? " and r.created_at < :at" : " and (r.created_at, r.id) < (:at, :id)");
		}
		sql.append(" order by r.created_at desc, r.id desc limit :limit");
		GenericExecuteSpec spec = db.sql(sql.toString()).bind("limit", limit);
		if (!states.isEmpty()) {
			spec = spec.bind("states", states.stream().map(Enum::name).toArray(String[]::new));
		}
		spec = bindCursor(spec, before);
		return spec.map(row -> new RunView(RunRows.run(row), RunRows.task(row))).all();
	}

	@Override
	public Flux<RunView> listUpdatedSince(io.agenticsdlc.core.domain.TaskOrigin origin, Cursor after, int limit) {
		GenericExecuteSpec spec = db.sql("select " + RunRows.RUN_COLUMNS + ", " + RunRows.TASK_COLUMNS
						+ " from runs r join tasks t on t.id = r.task_id where t.origin = :origin and "
						+ (after.id() == null ? "r.updated_at > :at" : "(r.updated_at, r.id) > (:at, :id)")
						+ " order by r.updated_at, r.id limit :limit")
				.bind("origin", origin.name())
				.bind("limit", limit);
		return bindCursor(spec, after).map(row -> new RunView(RunRows.run(row), RunRows.task(row))).all();
	}

	private static GenericExecuteSpec bindCursor(GenericExecuteSpec spec, Cursor cursor) {
		if (cursor == null) {
			return spec;
		}
		GenericExecuteSpec bound = spec.bind("at", timestamp(cursor.at()));
		return cursor.id() == null ? bound : bound.bind("id", cursor.id());
	}

	@Override
	public Mono<Run> update(Run current, Run next, List<RunEvent> events) {
		if (!current.id().equals(next.id())) {
			return Mono.error(new IllegalArgumentException("current and next must be the same run"));
		}
		GenericExecuteSpec spec = db.sql("""
				update runs set state = :state, risk = :risk, gates = :gates, pending_gate = :pendingGate,
				    resume_state = :resumeState, fix_iterations = :fixIterations, review_loops = :reviewLoops,
				    input_tokens = :inputTokens, output_tokens = :outputTokens, cache_read_tokens = :cacheReadTokens,
				    cache_write_tokens = :cacheWriteTokens, cost_micro_usd = :costMicroUsd,
				    last_event_seq = last_event_seq + :eventCount, version = version + 1, updated_at = :updatedAt
				where id = :id and version = :expectedVersion
				returning last_event_seq, version""")
				.bind("id", next.id())
				.bind("expectedVersion", current.version())
				.bind("state", next.state().name())
				.bind("fixIterations", next.fixIterations())
				.bind("reviewLoops", next.reviewLoops())
				.bind("inputTokens", next.usage().inputTokens())
				.bind("outputTokens", next.usage().outputTokens())
				.bind("cacheReadTokens", next.usage().cacheReadTokens())
				.bind("cacheWriteTokens", next.usage().cacheWriteTokens())
				.bind("costMicroUsd", next.usage().costMicroUsd())
				.bind("eventCount", events.size())
				.bind("updatedAt", timestamp(next.updatedAt()));
		spec = bindNullable(spec, "risk", name(next.risk()), String.class);
		spec = bindNullable(spec, "gates", RunRows.gates(next), String[].class);
		spec = bindNullable(spec, "pendingGate", name(next.pendingGate()), String.class);
		spec = bindNullable(spec, "resumeState", name(next.resumeState()), String.class);

		Mono<Run> work = spec.map(row -> new long[] { row.get("last_event_seq", Long.class), row.get("version", Long.class) })
				.one()
				.switchIfEmpty(Mono.error(() -> new ConcurrentRunUpdateException(current.id(), current.version())))
				.flatMap(seqAndVersion -> insertEvents(next.id(), seqAndVersion[0] - events.size() + 1, events)
						.then(notifyChange(next.id(), events.size()))
						.thenReturn(withVersion(next, seqAndVersion[1])));
		return tx.transactional(work);
	}

	@Override
	public Mono<Void> append(UUID runId, String leaseOwner, List<RunEvent> events) {
		if (events.isEmpty()) {
			return Mono.empty();
		}
		Mono<Void> work = db.sql("""
				update runs set last_event_seq = last_event_seq + :eventCount
				where id = :id and lease_owner = :owner and lease_expires_at > now() and state in %s
				returning last_event_seq""".formatted(WORKING_STATES))
				.bind("eventCount", events.size())
				.bind("id", runId)
				.bind("owner", leaseOwner)
				.map(row -> row.get("last_event_seq", Long.class))
				.one()
				.switchIfEmpty(Mono.error(() -> new LeaseLostException(runId, leaseOwner)))
				.flatMap(last -> insertEvents(runId, last - events.size() + 1, events))
				.then(notifyChange(runId, events.size()));
		return tx.transactional(work);
	}

	@Override
	public Flux<RunEvent> events(UUID runId, long afterSeq, int limit) {
		return db.sql("""
				select seq, type, actor, payload::text as payload, occurred_at from run_events
				where run_id = :runId and seq > :afterSeq order by seq limit :limit""")
				.bind("runId", runId)
				.bind("afterSeq", afterSeq)
				.bind("limit", limit)
				.map(row -> new RunEvent(row.get("seq", Long.class), runId,
						RunEventType.valueOf(row.get("type", String.class)), row.get("actor", String.class),
						codec.decode(row.get("payload", String.class)),
						row.get("occurred_at", OffsetDateTime.class).toInstant()))
				.all();
	}

	@Override
	public Mono<UUID> runWithPullRequest(String url) {
		return db.sql("""
				select run_id from run_events
				where type = 'ARTIFACT_PRODUCED' and payload->>'kind' = 'pull-request' and payload->>'url' = :url
				order by occurred_at desc limit 1""")
				.bind("url", url)
				.map(row -> row.get("run_id", UUID.class))
				.one();
	}

	@Override
	public Flux<RunEvent> latestEvents(UUID runId, Set<RunEventType> types, int limit) {
		return db.sql("""
				select * from (
				    select seq, type, actor, payload::text as payload, occurred_at from run_events
				    where run_id = :runId and type = any(:types) order by seq desc limit :limit) newest
				order by seq""")
				.bind("runId", runId)
				.bind("types", types.stream().map(Enum::name).toArray(String[]::new))
				.bind("limit", limit)
				.map(row -> new RunEvent(row.get("seq", Long.class), runId,
						RunEventType.valueOf(row.get("type", String.class)), row.get("actor", String.class),
						codec.decode(row.get("payload", String.class)),
						row.get("occurred_at", OffsetDateTime.class).toInstant()))
				.all();
	}

	@Override
	public Mono<Run> claim(String owner, Duration lease) {
		// The inner select repeats the partial index predicate verbatim so the planner can use runs_claimable_idx.
		// Oldest change first, so a run whose lease expired is not starved by newer runs.
		return db.sql("""
				update runs r set lease_owner = :owner, workspace_node = :node,
				    lease_expires_at = now() + make_interval(secs => :leaseSeconds), version = r.version + 1
				where r.id = (
				    select id from runs
				    where state in %s and (lease_expires_at is null or lease_expires_at < now())
				      and (workspace_node is null or workspace_node = :node or not exists (
				          select 1 from worker_nodes n where n.node = runs.workspace_node
				          and n.heartbeat_at > now() - make_interval(secs => :nodeTimeoutSeconds)))
				    order by updated_at, id
				    limit 1
				    for update skip locked)
				returning %s""".formatted(WORKING_STATES, RunRows.RUN_COLUMNS))
				.bind("owner", owner)
				.bind("node", node)
				.bind("nodeTimeoutSeconds", (double) nodeTimeout.toMillis() / 1000)
				.bind("leaseSeconds", (double) lease.toMillis() / 1000)
				.map(RunRows::run)
				.one();
	}

	@Override
	public Mono<Boolean> renewLease(UUID runId, String owner, Duration lease) {
		return db.sql("""
				update runs set lease_expires_at = now() + make_interval(secs => :leaseSeconds)
				where id = :id and lease_owner = :owner and lease_expires_at > now() and state in %s"""
				.formatted(WORKING_STATES))
				.bind("leaseSeconds", (double) lease.toMillis() / 1000)
				.bind("id", runId)
				.bind("owner", owner)
				.fetch().rowsUpdated()
				.map(updated -> updated == 1);
	}

	@Override
	public Mono<Void> releaseLease(UUID runId, String owner) {
		return db.sql("update runs set lease_owner = null, lease_expires_at = null where id = :id and lease_owner = :owner")
				.bind("id", runId)
				.bind("owner", owner)
				.then();
	}

	private Mono<RunView> existing(Task task) {
		return db.sql("select " + RunRows.RUN_COLUMNS + ", " + RunRows.TASK_COLUMNS + """
				 from tasks t join runs r on r.task_id = t.id
				where t.requested_by = :requestedBy and t.idempotency_key = :key
				order by r.created_at desc limit 1""")
				.bind("requestedBy", task.requestedBy())
				.bind("key", task.idempotencyKey())
				.map(row -> new RunView(RunRows.run(row), RunRows.task(row)))
				.one();
	}

	private Mono<Void> insertTask(Task task) {
		GenericExecuteSpec spec = db.sql("""
				insert into tasks (id, origin, external_ref, title, description, scm_kind, clone_url, base_branch,
				    trust, requested_by, idempotency_key, created_at, companions)
				values (:id, :origin, :externalRef, :title, :description, :scmKind, :cloneUrl, :baseBranch,
				    :trust, :requestedBy, :idempotencyKey, :createdAt, :companions)""")
				.bind("companions", Json.of(RunRows.companionsJson(task.companions())))
				.bind("id", task.id())
				.bind("origin", task.origin().name())
				.bind("title", EventPayloadCodec.stripNul(task.title()))
				.bind("description", EventPayloadCodec.stripNul(task.description()))
				.bind("scmKind", task.repository().kind().name())
				.bind("cloneUrl", task.repository().cloneUrl().toString())
				.bind("trust", task.trust().name())
				.bind("requestedBy", task.requestedBy())
				.bind("createdAt", timestamp(task.createdAt()));
		spec = bindNullable(spec, "externalRef", task.externalRef(), String.class);
		spec = bindNullable(spec, "baseBranch", task.baseBranch(), String.class);
		spec = bindNullable(spec, "idempotencyKey", task.idempotencyKey(), String.class);
		return spec.then();
	}

	private Mono<Void> insertRun(Run run, int eventCount) {
		GenericExecuteSpec spec = db.sql("""
				insert into runs (id, task_id, state, risk, gates, pending_gate, resume_state, last_event_seq,
				    version, created_at, updated_at)
				values (:id, :taskId, :state, :risk, :gates, :pendingGate, :resumeState, :lastEventSeq, 0,
				    :createdAt, :updatedAt)""")
				.bind("id", run.id())
				.bind("taskId", run.taskId())
				.bind("state", run.state().name())
				.bind("lastEventSeq", eventCount)
				.bind("createdAt", timestamp(run.createdAt()))
				.bind("updatedAt", timestamp(run.updatedAt()));
		spec = bindNullable(spec, "risk", name(run.risk()), String.class);
		spec = bindNullable(spec, "gates", RunRows.gates(run), String[].class);
		spec = bindNullable(spec, "pendingGate", name(run.pendingGate()), String.class);
		spec = bindNullable(spec, "resumeState", name(run.resumeState()), String.class);
		return spec.then();
	}

	private Mono<Void> insertEvents(UUID runId, long firstSeq, List<RunEvent> events) {
		return Flux.range(0, events.size())
				.concatMap(i -> {
					RunEvent e = events.get(i);
					return db.sql("""
							insert into run_events (run_id, seq, type, actor, payload, occurred_at)
							values (:runId, :seq, :type, :actor, :payload, :occurredAt)""")
							.bind("runId", runId)
							.bind("seq", firstSeq + i)
							.bind("type", e.type().name())
							.bind("actor", e.actor())
							.bind("payload", Json.of(codec.encode(e.payload())))
							.bind("occurredAt", timestamp(e.occurredAt()))
							.then();
				})
				.then();
	}

	private Mono<Void> notifyChange(UUID runId, int eventCount) {
		if (eventCount == 0) {
			return Mono.empty();
		}
		return db.sql("select pg_notify('" + CHANNEL + "', :runId)").bind("runId", runId.toString()).then();
	}

	private static <T> GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, T value, Class<T> type) {
		return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
	}

	private static Run withVersion(Run r, long version) {
		return new Run(r.id(), r.taskId(), r.state(), r.risk(), r.gatePolicy(), r.pendingGate(), r.resumeState(),
				r.fixIterations(), r.reviewLoops(), r.usage(), version, r.createdAt(), r.updatedAt());
	}
}
