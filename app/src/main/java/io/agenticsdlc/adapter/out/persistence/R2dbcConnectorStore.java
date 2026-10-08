package io.agenticsdlc.adapter.out.persistence;

import static io.agenticsdlc.adapter.out.persistence.RunRows.timestamp;

import io.agenticsdlc.config.connectors.ConnectorStore;
import io.r2dbc.postgresql.codec.Json;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link ConnectorStore} in Postgres ({@code connectors}, {@code app_secrets}). */
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

	@Override
	public Mono<Map<String, String>> appSecrets() {
		return db.sql("select name, value from app_secrets")
				.map(row -> Map.entry(row.get("name", String.class), row.get("value", String.class))).all()
				.collectMap(Map.Entry::getKey, Map.Entry::getValue);
	}

	@Override
	public Mono<Void> replaceAppSecret(String name, String value) {
		return db.sql("update app_secrets set value = :value where name = :name").bind("value", value)
				.bind("name", name).then();
	}

	@Override
	public Mono<Void> replaceSecrets(String connectorId, String encryptedSecrets) {
		return db.sql("update connectors set secrets = :secrets where id = :id").bind("secrets", encryptedSecrets)
				.bind("id", connectorId).then();
	}
}
