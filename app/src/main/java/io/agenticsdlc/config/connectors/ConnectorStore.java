package io.agenticsdlc.config.connectors;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Storage of connectors, local users and small app secrets (ADR-0007). Secrets are stored already encrypted. */
public interface ConnectorStore {

	/** @param encryptedSecrets {@link SecretBox}-sealed JSON object of secret values; null when none */
	record StoredConnector(String id, String status, Map<String, Object> config, String encryptedSecrets,
			Instant updatedAt, String updatedBy) {
	}

	record LocalUser(String username, String passwordHash, List<String> roles, Instant createdAt) {
	}

	Flux<StoredConnector> connectors();

	Mono<Void> save(StoredConnector connector);

	Mono<Void> delete(String id);

	Mono<Long> userCount();

	Mono<LocalUser> user(String username);

	/** Emits false when the user already exists. */
	Mono<Boolean> createUser(LocalUser user);

	Mono<String> appSecret(String name);

	/** Stores {@code value} unless the secret exists; emits the stored value either way. */
	Mono<String> appSecretIfAbsent(String name, String value);
}
