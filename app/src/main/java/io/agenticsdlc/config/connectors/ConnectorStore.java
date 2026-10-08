package io.agenticsdlc.config.connectors;

import java.time.Instant;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Storage of connectors and small app secrets (ADR-0007). Secrets are stored already encrypted. */
public interface ConnectorStore {

	/** @param encryptedSecrets {@link SecretBox}-sealed JSON object of secret values; null when none */
	record StoredConnector(String id, String status, Map<String, Object> config, String encryptedSecrets,
			Instant updatedAt, String updatedBy) {
	}

	Flux<StoredConnector> connectors();

	Mono<Void> save(StoredConnector connector);

	Mono<Void> delete(String id);

	Mono<String> appSecret(String name);

	/** Name to sealed value of every app secret. */
	Mono<Map<String, String>> appSecrets();

	Mono<Void> replaceAppSecret(String name, String value);

	Mono<Void> replaceSecrets(String connectorId, String encryptedSecrets);

	/** Stores {@code value} unless the secret exists; emits the stored value either way. */
	Mono<String> appSecretIfAbsent(String name, String value);
}
