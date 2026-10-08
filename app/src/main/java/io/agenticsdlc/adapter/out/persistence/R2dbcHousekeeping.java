package io.agenticsdlc.adapter.out.persistence;

import static io.agenticsdlc.adapter.out.persistence.RunRows.timestamp;

import io.agenticsdlc.config.Housekeeping;
import java.time.Instant;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** {@link Housekeeping} in Postgres: one statement per batch, so a batch is deleted entirely or not at all. */
@Repository
class R2dbcHousekeeping implements Housekeeping {

	private final DatabaseClient db;
	private final TransactionalOperator tx;

	R2dbcHousekeeping(DatabaseClient db, TransactionalOperator tx) {
		this.db = db;
		this.tx = tx;
	}

	@Override
	public Mono<Long> deleteFinishedRuns(Instant cutoff, int batch) {
		return db.sql("""
				with doomed as (
				    select id, task_id from runs
				    where state in ('DONE', 'FAILED', 'CANCELLED') and updated_at < :cutoff
				    order by updated_at limit :batch for update skip locked),
				facts as (update repo_facts set source_run_id = null where source_run_id in (select id from doomed)),
				cursors as (delete from ticket_sync where run_id in (select id from doomed)),
				events as (delete from run_events where run_id in (select id from doomed)),
				gone as (delete from runs where id in (select id from doomed) returning task_id),
				orphans as (
				    delete from tasks t where t.id in (select task_id from gone)
				    and not exists (select 1 from runs r where r.task_id = t.id and r.id not in (select id from doomed)))
				select count(*) as n from gone""")
				.bind("cutoff", timestamp(cutoff)).bind("batch", batch)
				.map(row -> row.get("n", Long.class)).one()
				.as(tx::transactional);
	}

	@Override
	public Mono<Long> deleteDeadTokens(Instant cutoff) {
		return db.sql("delete from api_tokens where coalesce(revoked_at, expires_at) < :cutoff")
				.bind("cutoff", timestamp(cutoff)).fetch().rowsUpdated();
	}
}
