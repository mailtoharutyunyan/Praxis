package io.agenticsdlc.adapter.out.persistence;

import java.time.Duration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/** Cluster coordination in Postgres: node heartbeats (workspace affinity) and leases on singleton background jobs. */
@Repository
public class R2dbcCoordination {

	private final DatabaseClient db;

	R2dbcCoordination(DatabaseClient db) {
		this.db = db;
	}

	/** Records that {@code node} is alive; its runs stay with it while heartbeats are recent. */
	public Mono<Void> heartbeat(String node) {
		return db.sql("""
				insert into worker_nodes (node, heartbeat_at) values (:node, now())
				on conflict (node) do update set heartbeat_at = now()""")
				.bind("node", node)
				.then();
	}

	/** Takes or renews the lease on {@code job} for {@code ttl}; false while another owner's lease is unexpired. */
	public Mono<Boolean> tryAcquireJob(String job, String owner, Duration ttl) {
		return db.sql("""
				insert into job_leases (name, owner, expires_at)
				values (:name, :owner, now() + make_interval(secs => :ttlSeconds))
				on conflict (name) do update set owner = excluded.owner, expires_at = excluded.expires_at
				where job_leases.owner = excluded.owner or job_leases.expires_at < now()
				returning owner""")
				.bind("name", job)
				.bind("owner", owner)
				.bind("ttlSeconds", (double) ttl.toMillis() / 1000)
				.map(row -> row.get("owner", String.class))
				.one()
				.map(owner::equals)
				.defaultIfEmpty(false);
	}
}
