package io.agenticsdlc.adapter.out.persistence;

import static io.agenticsdlc.adapter.out.persistence.RunRows.timestamp;

import io.agenticsdlc.core.insights.DailyCount;
import io.agenticsdlc.core.insights.InsightsStore;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@link InsightsStore} in Postgres: every figure is aggregated by one query, nothing is loaded row by row. */
@Repository
class R2dbcInsightsStore implements InsightsStore {

	/** Runs created in {@code [:from, :to)}; uses {@code runs_created_at_idx}. */
	private static final String WINDOW = "r.created_at >= :from and r.created_at < :to";

	private final DatabaseClient db;

	R2dbcInsightsStore(DatabaseClient db) {
		this.db = db;
	}

	@Override
	public Mono<Totals> totals(Instant from, Instant to) {
		return db.sql("""
				select count(*) as started,
				    count(*) filter (where r.state = 'DONE') as done,
				    count(*) filter (where r.state = 'FAILED') as failed,
				    count(*) filter (where r.state = 'CANCELLED') as cancelled,
				    coalesce(sum(r.cost_micro_usd), 0)::bigint as cost,
				    coalesce(sum(r.input_tokens + r.output_tokens + r.cache_read_tokens + r.cache_write_tokens), 0)::bigint
				        as tokens
				from runs r where\s""" + WINDOW)
				.bind("from", timestamp(from)).bind("to", timestamp(to))
				.map(row -> new Totals(row.get("started", Long.class), row.get("done", Long.class),
						row.get("failed", Long.class), row.get("cancelled", Long.class), row.get("cost", Long.class),
						row.get("tokens", Long.class)))
				.one();
	}

	@Override
	public Flux<Double> minutesToPullRequest(Instant from, Instant to) {
		// The event predicate repeats run_events_pr_open_idx verbatim.
		return db.sql("""
				select (extract(epoch from min(e.occurred_at) - r.created_at) / 60.0)::float8 as minutes
				from runs r join run_events e on e.run_id = r.id
				    and e.type = 'STATE_CHANGED' and e.payload ->> 'to' = 'PR_OPEN'
				where\s""" + WINDOW + " group by r.id, r.created_at")
				.bind("from", timestamp(from)).bind("to", timestamp(to))
				.map(row -> row.get("minutes", Double.class))
				.all();
	}

	@Override
	public Flux<DailyCount> runsPerDay(Instant from, Instant to) {
		return db.sql("select (r.created_at at time zone 'UTC')::date as day, count(*) as n from runs r where "
				+ WINDOW + " group by day order by day")
				.bind("from", timestamp(from)).bind("to", timestamp(to))
				.map(row -> new DailyCount(row.get("day", LocalDate.class), row.get("n", Long.class)))
				.all();
	}
}
