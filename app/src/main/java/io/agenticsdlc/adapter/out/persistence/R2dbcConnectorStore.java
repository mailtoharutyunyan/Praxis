package io.agenticsdlc.adapter.out.persistence;

import static io.agenticsdlc.adapter.out.persistence.RunRows.timestamp;

import io.agenticsdlc.config.connectors.ConnectorStore;
import io.r2dbc.postgresql.codec.Json;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link ConnectorStore} in Postgres ({@code connectors}, {@code local_users}, {@code app_secrets}). */
@Repository
class R2dbcConnectorStore implements ConnectorStore {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final DatabaseClient db;
	private final JsonMapper json;

	R2dbcConnectorStore(DatabaseClient db, JsonMapper json) {
		this.db = db;
		this.json = json;
	}

	@Override
	public Flux<StoredConnector> connectors() {
		return db.sql("select id, status, config::text as config, secrets, updated_at, updated_by from connectors")
				.map(row -> new StoredConnector(row.get("id", String.class), row.get("status", String.class),
						json.readValue(row.get("config", String.class), MAP), row.get("secrets", String.class),
						row.get("updated_at", OffsetDateTime.class).toInstant(), row.get("updated_by", String.class)))
				.all();
	}

	@Override
	public Mono<Void> save(StoredConnector connector) {
		var spec = db.sql("""
				insert into connectors (id, status, config, secrets, updated_at, updated_by)
				values (:id, :status, :config, :secrets, :updatedAt, :updatedBy)
				on conflict (id) do update set status = excluded.status, config = excluded.config,
				    secrets = excluded.secrets, updated_at = excluded.updated_at, updated_by = excluded.updated_by""")
				.bind("id", connector.id())
				.bind("status", connector.status())
				.bind("config", Json.of(json.writeValueAsString(connector.config())))
				.bind("updatedAt", timestamp(connector.updatedAt()))
				.bind("updatedBy", connector.updatedBy());
		spec = connector.encryptedSecrets() == null ? spec.bindNull("secrets", String.class)
				: spec.bind("secrets", connector.encryptedSecrets());
		return spec.then();
	}

	@Override
	public Mono<Void> delete(String id) {
		return db.sql("delete from connectors where id = :id").bind("id", id).then();
	}

	@Override
	public Mono<Long> userCount() {
		return db.sql("select count(*) as n from local_users").map(row -> row.get("n", Long.class)).one();
	}

	@Override
	public Mono<LocalUser> user(String username) {
		return db.sql("select username, password_hash, roles, created_at from local_users where username = :username")
				.bind("username", username)
				.map(row -> new LocalUser(row.get("username", String.class), row.get("password_hash", String.class),
						List.of(Arrays.stream(row.get("roles", String.class).split(",")).map(String::strip)
								.filter(r -> !r.isEmpty()).toArray(String[]::new)),
						row.get("created_at", OffsetDateTime.class).toInstant()))
				.one();
	}

	@Override
	public Mono<Boolean> createUser(LocalUser user) {
		return db.sql("""
				insert into local_users (username, password_hash, roles, created_at)
				values (:username, :hash, :roles, :createdAt) on conflict (username) do nothing""")
				.bind("username", user.username())
				.bind("hash", user.passwordHash())
				.bind("roles", String.join(",", user.roles()))
				.bind("createdAt", timestamp(user.createdAt()))
				.fetch().rowsUpdated().map(n -> n == 1);
	}

	@Override
	public Mono<String> appSecret(String name) {
		return db.sql("select value from app_secrets where name = :name").bind("name", name)
				.map(row -> row.get("value", String.class)).one();
	}

	@Override
	public Mono<String> appSecretIfAbsent(String name, String value) {
		return db.sql("insert into app_secrets (name, value, created_at) values (:name, :value, :now) on conflict (name) do nothing")
				.bind("name", name).bind("value", value).bind("now", timestamp(Instant.now()))
				.then()
				.then(appSecret(name));
	}
}
