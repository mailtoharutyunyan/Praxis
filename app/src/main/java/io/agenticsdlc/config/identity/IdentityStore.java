package io.agenticsdlc.config.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Built-in accounts and personal API tokens. */
public interface IdentityStore {

	/** @param sessionsValidAfter sign-in tokens issued before this are no longer accepted */
	record LocalUser(String username, String passwordHash, List<String> roles, Instant createdAt, int failedAttempts,
			Instant lockedUntil, Instant sessionsValidAfter) {
	}

	/** @param tokenHash hex SHA-256 of the token; the token itself is never stored */
	record ApiToken(UUID id, String name, String owner, List<String> roles, String tokenHash, String hint,
			Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {
	}

	Mono<Long> userCount();

	Flux<LocalUser> users();

	Mono<LocalUser> user(String username);

	/** Emits false when the user already exists. */
	Mono<Boolean> createUser(String username, String passwordHash, List<String> roles, Instant createdAt);

	/** Also ends the user's sessions: a role change must not linger in tokens already issued. */
	Mono<Boolean> updateRoles(String username, List<String> roles, Instant sessionsValidAfter);

	Mono<Boolean> updatePassword(String username, String passwordHash, Instant sessionsValidAfter);

	Mono<Boolean> deleteUser(String username);

	Mono<Void> endSessions(String username, Instant sessionsValidAfter);

	/** Counts a failed sign-in; the {@code max}-th consecutive one locks the account until {@code now + lockout}. */
	Mono<Void> recordFailure(String username, Instant now, int max, Duration lockout);

	Mono<Void> recordSuccess(String username);

	Mono<Void> createToken(ApiToken token);

	Mono<ApiToken> tokenByHash(String tokenHash);

	/** All tokens of an owner, or of everyone when {@code owner} is null; newest first. */
	Flux<ApiToken> tokens(String owner);

	/** Emits false if no live token matched (with {@code owner} set, only that owner's tokens match). */
	Mono<Boolean> revokeToken(UUID id, String owner, Instant now);

	Mono<Void> revokeTokensOf(String owner, Instant now);

	Mono<Void> touchToken(UUID id, Instant now);
}
