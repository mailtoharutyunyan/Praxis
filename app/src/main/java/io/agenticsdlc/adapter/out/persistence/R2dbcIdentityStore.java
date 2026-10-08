package io.agenticsdlc.adapter.out.persistence;

import static io.agenticsdlc.adapter.out.persistence.RunRows.timestamp;

import io.agenticsdlc.config.identity.IdentityStore;
import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@link IdentityStore} in Postgres ({@code local_users}, {@code api_tokens}). */
@Repository
class R2dbcIdentityStore implements IdentityStore {

	private static final String USER_COLUMNS = "username, password_hash, roles, created_at, failed_attempts, "
			+ "locked_until, sessions_valid_after";
	private static final String TOKEN_COLUMNS = "id, name, owner, roles, token_hash, hint, created_at, expires_at, "
			+ "last_used_at, revoked_at";

	private final DatabaseClient db;

	R2dbcIdentityStore(DatabaseClient db) {
		this.db = db;
	}

	private static List<String> roles(String csv) {
		return Arrays.stream(csv.split(",")).map(String::strip).filter(r -> !r.isEmpty()).toList();
	}

	private static Instant instant(Readable row, String column) {
		OffsetDateTime value = row.get(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static LocalUser user(Readable row) {
		return new LocalUser(row.get("username", String.class), row.get("password_hash", String.class),
				roles(row.get("roles", String.class)), instant(row, "created_at"),
				row.get("failed_attempts", Integer.class), instant(row, "locked_until"),
				instant(row, "sessions_valid_after"));
	}

	private static ApiToken token(Readable row) {
		return new ApiToken(row.get("id", UUID.class), row.get("name", String.class), row.get("owner", String.class),
				roles(row.get("roles", String.class)), row.get("token_hash", String.class), row.get("hint", String.class),
				instant(row, "created_at"), instant(row, "expires_at"), instant(row, "last_used_at"),
				instant(row, "revoked_at"));
	}

	@Override
	public Mono<Long> userCount() {
		return db.sql("select count(*) as n from local_users").map(row -> row.get("n", Long.class)).one();
	}

	@Override
	public Flux<LocalUser> users() {
		return db.sql("select " + USER_COLUMNS + " from local_users order by username").map(row -> user(row))
				.all();
	}

	@Override
	public Mono<LocalUser> user(String username) {
		return db.sql("select " + USER_COLUMNS + " from local_users where username = :username")
				.bind("username", username).map(row -> user(row)).one();
	}

	@Override
	public Mono<Boolean> createUser(String username, String passwordHash, List<String> roles, Instant createdAt) {
		return db.sql("""
				insert into local_users (username, password_hash, roles, created_at)
				values (:username, :hash, :roles, :createdAt) on conflict (username) do nothing""")
				.bind("username", username)
				.bind("hash", passwordHash)
				.bind("roles", String.join(",", roles))
				.bind("createdAt", timestamp(createdAt))
				.fetch().rowsUpdated().map(n -> n == 1);
	}

	@Override
	public Mono<Boolean> updateRoles(String username, List<String> roles, Instant sessionsValidAfter) {
		return db.sql("update local_users set roles = :roles, sessions_valid_after = :after where username = :username")
				.bind("roles", String.join(",", roles)).bind("after", timestamp(sessionsValidAfter))
				.bind("username", username).fetch().rowsUpdated().map(n -> n == 1);
	}

	@Override
	public Mono<Boolean> updatePassword(String username, String passwordHash, Instant sessionsValidAfter) {
		return db.sql("""
				update local_users set password_hash = :hash, sessions_valid_after = :after, failed_attempts = 0,
				    locked_until = null where username = :username""")
				.bind("hash", passwordHash).bind("after", timestamp(sessionsValidAfter)).bind("username", username)
				.fetch().rowsUpdated().map(n -> n == 1);
	}

	@Override
	public Mono<Boolean> deleteUser(String username) {
		return db.sql("delete from local_users where username = :username").bind("username", username)
				.fetch().rowsUpdated().map(n -> n == 1);
	}

	@Override
	public Mono<Void> endSessions(String username, Instant sessionsValidAfter) {
		return db.sql("update local_users set sessions_valid_after = :after where username = :username")
				.bind("after", timestamp(sessionsValidAfter)).bind("username", username).then();
	}

	@Override
	public Mono<Void> recordFailure(String username, Instant now, int max, Duration lockout) {
		return db.sql("""
				update local_users set
				    locked_until = case when failed_attempts + 1 >= :max then :until else locked_until end,
				    failed_attempts = case when failed_attempts + 1 >= :max then 0 else failed_attempts + 1 end
				where username = :username""")
				.bind("max", max).bind("until", timestamp(now.plus(lockout))).bind("username", username).then();
	}

	@Override
	public Mono<Void> recordSuccess(String username) {
		return db.sql("update local_users set failed_attempts = 0, locked_until = null where username = :username")
				.bind("username", username).then();
	}

	@Override
	public Mono<Void> createToken(ApiToken token) {
		return db.sql("""
				insert into api_tokens (id, name, owner, roles, token_hash, hint, created_at, expires_at)
				values (:id, :name, :owner, :roles, :hash, :hint, :createdAt, :expiresAt)""")
				.bind("id", token.id()).bind("name", token.name()).bind("owner", token.owner())
				.bind("roles", String.join(",", token.roles())).bind("hash", token.tokenHash()).bind("hint", token.hint())
				.bind("createdAt", timestamp(token.createdAt())).bind("expiresAt", timestamp(token.expiresAt()))
				.then();
	}

	@Override
	public Mono<ApiToken> tokenByHash(String tokenHash) {
		return db.sql("select " + TOKEN_COLUMNS + " from api_tokens where token_hash = :hash").bind("hash", tokenHash)
				.map(row -> token(row)).one();
	}

	@Override
	public Flux<ApiToken> tokens(String owner) {
		var spec = owner == null
				? db.sql("select " + TOKEN_COLUMNS + " from api_tokens order by created_at desc")
				: db.sql("select " + TOKEN_COLUMNS + " from api_tokens where owner = :owner order by created_at desc")
						.bind("owner", owner);
		return spec.map(row -> token(row)).all();
	}

	@Override
	public Mono<Boolean> revokeToken(UUID id, String owner, Instant now) {
		var spec = owner == null
				? db.sql("update api_tokens set revoked_at = :now where id = :id and revoked_at is null")
				: db.sql("update api_tokens set revoked_at = :now where id = :id and owner = :owner and revoked_at is null")
						.bind("owner", owner);
		return spec.bind("now", timestamp(now)).bind("id", id).fetch().rowsUpdated().map(n -> n == 1);
	}

	@Override
	public Mono<Void> revokeTokensOf(String owner, Instant now) {
		return db.sql("update api_tokens set revoked_at = :now where owner = :owner and revoked_at is null")
				.bind("now", timestamp(now)).bind("owner", owner).then();
	}

	@Override
	public Mono<Void> touchToken(UUID id, Instant now) {
		return db.sql("update api_tokens set last_used_at = :now where id = :id").bind("now", timestamp(now))
				.bind("id", id).then();
	}
}
