package io.agenticsdlc.adapter.out.persistence;

import io.agenticsdlc.core.intake.TicketSyncStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
class R2dbcTicketSyncStore implements TicketSyncStore {

	private final DatabaseClient db;

	R2dbcTicketSyncStore(DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Mono<Long> lastReported(UUID runId) {
		return db.sql("select last_seq from ticket_sync where run_id = :runId")
				.bind("runId", runId)
				.map(row -> row.get("last_seq", Long.class))
				.one()
				.defaultIfEmpty(0L);
	}

	/** Monotonic: a slower concurrent sweep can never move the cursor backwards. */
	@Override
	public Mono<Void> markReported(UUID runId, long seq) {
		return db.sql("""
				insert into ticket_sync (run_id, last_seq, updated_at) values (:runId, :seq, :now)
				on conflict (run_id) do update set last_seq = greatest(ticket_sync.last_seq, excluded.last_seq),
				    updated_at = excluded.updated_at""")
				.bind("runId", runId)
				.bind("seq", seq)
				.bind("now", OffsetDateTime.now(ZoneOffset.UTC))
				.then();
	}
}
