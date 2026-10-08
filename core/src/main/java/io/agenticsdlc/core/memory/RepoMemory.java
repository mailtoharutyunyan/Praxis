package io.agenticsdlc.core.memory;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Storage of learned repository facts. */
public interface RepoMemory {

	/** Store a new fact as {@link RepoFact.Status#CANDIDATE}. */
	Mono<Void> propose(RepoFact fact);

	/** How many facts a run has proposed, to cap them. */
	Mono<Long> countFromRun(UUID runId);

	/** Active, unexpired facts of a repository, most recently used first. */
	Flux<RepoFact> active(String repository, Instant now, int limit);

	/** All facts of a repository (any status), newest first; {@code repository} null for every repository. */
	Flux<RepoFact> list(String repository, int limit);

	/** Settle a run's candidates: activated if its pull request was merged, disabled otherwise. */
	Mono<Long> settleRun(UUID runId, boolean merged, Instant expiresAt);

	/** A fact was checked against the code and used: keep it for another period. */
	Mono<Void> used(UUID id, Instant expiresAt);

	/** An approver's decision; activating also renews the expiry. Empty if there is no such fact. */
	Mono<RepoFact> setStatus(UUID id, RepoFact.Status status, Instant expiresAt);
}
