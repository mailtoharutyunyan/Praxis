package io.agenticsdlc.adapter.out.persistence;

import static io.agenticsdlc.adapter.out.persistence.RunRows.timestamp;

import io.agenticsdlc.core.memory.RepoFact;
import io.agenticsdlc.core.memory.RepoMemory;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link RepoMemory} in Postgres ({@code repo_facts}); citations are stored as JSON. */
@Repository
class R2dbcRepoMemory implements RepoMemory {

	private static final TypeReference<List<Map<String, Object>>> CITATIONS = new TypeReference<>() {
	};
	private static final String COLUMNS = "id, repository, fact, citations::text as citations, status, source_run_id, "
			+ "created_at, expires_at";

	private final DatabaseClient db;
	private final JsonMapper json;

	R2dbcRepoMemory(DatabaseClient db, JsonMapper json) {
		this.db = db;
		this.json = json;
	}

	@Override
	public Mono<Void> propose(RepoFact fact) {
		List<Map<String, Object>> citations = fact.citations().stream()
				.map(c -> Map.<String, Object>of("path", c.path(), "line", c.line(), "snippet", c.snippet())).toList();
		GenericExecuteSpec spec = db.sql("""
				insert into repo_facts (id, repository, fact, citations, status, source_run_id, created_at, expires_at)
				values (:id, :repository, :fact, :citations, :status, :sourceRunId, :createdAt, :expiresAt)""")
				.bind("id", fact.id())
				.bind("repository", fact.repository())
				.bind("fact", fact.fact())
				.bind("citations", Json.of(json.writeValueAsString(citations)))
				.bind("status", fact.status().name())
				.bind("createdAt", timestamp(fact.createdAt()));
		spec = fact.sourceRunId() == null ? spec.bindNull("sourceRunId", UUID.class) : spec.bind("sourceRunId", fact.sourceRunId());
		spec = fact.expiresAt() == null ? spec.bindNull("expiresAt", OffsetDateTime.class)
				: spec.bind("expiresAt", timestamp(fact.expiresAt()));
		return spec.then();
	}

	@Override
	public Mono<Long> countFromRun(UUID runId) {
		return db.sql("select count(*) as n from repo_facts where source_run_id = :runId").bind("runId", runId)
				.map(row -> row.get("n", Long.class)).one();
	}

	@Override
	public Flux<RepoFact> active(String repository, Instant now, int limit) {
		return db.sql("select " + COLUMNS + """
				 from repo_facts where repository = :repository and status = 'ACTIVE' and expires_at > :now
				order by coalesce(last_used_at, created_at) desc limit :limit""")
				.bind("repository", repository)
				.bind("now", timestamp(now))
				.bind("limit", limit)
				.map(this::fact).all();
	}

	@Override
	public Flux<RepoFact> list(String repository, int limit) {
		GenericExecuteSpec spec = db.sql("select " + COLUMNS + " from repo_facts"
				+ (repository == null ? "" : " where repository = :repository") + " order by created_at desc limit :limit")
				.bind("limit", limit);
		if (repository != null) {
			spec = spec.bind("repository", repository);
		}
		return spec.map(this::fact).all();
	}

	@Override
	public Mono<Long> settleRun(UUID runId, boolean merged, Instant expiresAt) {
		return db.sql("""
				update repo_facts set status = :status, expires_at = :expiresAt
				where source_run_id = :runId and status = 'CANDIDATE'""")
				.bind("status", merged ? "ACTIVE" : "DISABLED")
				.bind("expiresAt", timestamp(expiresAt))
				.bind("runId", runId)
				.fetch().rowsUpdated();
	}

	@Override
	public Mono<Void> used(UUID id, Instant expiresAt) {
		return db.sql("update repo_facts set last_used_at = now(), expires_at = greatest(expires_at, :expiresAt) where id = :id")
				.bind("expiresAt", timestamp(expiresAt))
				.bind("id", id)
				.then();
	}

	@Override
	public Mono<RepoFact> setStatus(UUID id, RepoFact.Status status, Instant expiresAt) {
		return db.sql("update repo_facts set status = :status, expires_at = :expiresAt where id = :id returning "
				+ COLUMNS)
				.bind("status", status.name())
				.bind("expiresAt", timestamp(expiresAt))
				.bind("id", id)
				.map(this::fact).one();
	}

	private RepoFact fact(Readable row) {
		List<RepoFact.Citation> citations = new ArrayList<>();
		for (Map<String, Object> c : json.readValue(row.get("citations", String.class), CITATIONS)) {
			citations.add(new RepoFact.Citation(String.valueOf(c.get("path")), ((Number) c.get("line")).intValue(),
					String.valueOf(c.get("snippet"))));
		}
		OffsetDateTime expires = row.get("expires_at", OffsetDateTime.class);
		return new RepoFact(row.get("id", UUID.class), row.get("repository", String.class), row.get("fact", String.class),
				citations, RepoFact.Status.valueOf(row.get("status", String.class)), row.get("source_run_id", UUID.class),
				row.get("created_at", OffsetDateTime.class).toInstant(), expires == null ? null : expires.toInstant());
	}
}
