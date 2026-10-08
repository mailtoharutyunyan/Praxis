package io.agenticsdlc.config.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretBoxTest {

	@TempDir
	Path dir;

	@Test
	void roundTripsWithAFreshIvEachTime() {
		SecretBox box = new SecretBox(new byte[32]);
		String first = box.encrypt("token-value");
		assertThat(first).isNotEqualTo(box.encrypt("token-value")).doesNotContain("token-value");
		assertThat(box.decrypt(first)).isEqualTo("token-value");
	}

	@Test
	void generatesAKeyFileOnceAndReusesIt() throws Exception {
		Path file = dir.resolve("data/secrets.key");
		String sealed = SecretBox.load("", file).encrypt("x");
		assertThat(Base64.getDecoder().decode(Files.readString(file).strip())).hasSize(32);
		assertThat(SecretBox.load(null, file).decrypt(sealed)).isEqualTo("x");
	}

	@Test
	void aPreviousKeyStillDecryptsAndRotationReencryptsWithTheCurrentOne() {
		byte[] oldKey = new byte[32];
		byte[] newKey = new byte[32];
		newKey[0] = 7;
		String sealedBefore = new SecretBox(oldKey).encrypt("token");
		SecretBox rotated = new SecretBox(newKey, java.util.List.of(oldKey));
		assertThat(rotated.decrypt(sealedBefore)).isEqualTo("token");
		assertThat(rotated.sealedWithCurrentKey(sealedBefore)).isFalse();

		java.util.Map<String, String> appSecrets = new java.util.HashMap<>(java.util.Map.of("signing", sealedBefore));
		java.util.Map<String, ConnectorStore.StoredConnector> connectors = new java.util.HashMap<>(java.util.Map.of("git",
				new ConnectorStore.StoredConnector("git", "CONFIGURED", java.util.Map.of(), sealedBefore,
						java.time.Instant.now(), "admin")));
		ConnectorStore store = new ConnectorStore() {
			public reactor.core.publisher.Flux<StoredConnector> connectors() {
				return reactor.core.publisher.Flux.fromIterable(connectors.values());
			}

			public reactor.core.publisher.Mono<Void> save(StoredConnector connector) {
				return reactor.core.publisher.Mono.empty();
			}

			public reactor.core.publisher.Mono<Void> delete(String id) {
				return reactor.core.publisher.Mono.empty();
			}

			public reactor.core.publisher.Mono<String> appSecret(String name) {
				return reactor.core.publisher.Mono.justOrEmpty(appSecrets.get(name));
			}

			public reactor.core.publisher.Mono<String> appSecretIfAbsent(String name, String value) {
				return appSecret(name);
			}

			public reactor.core.publisher.Mono<java.util.Map<String, String>> appSecrets() {
				return reactor.core.publisher.Mono.just(java.util.Map.copyOf(appSecrets));
			}

			public reactor.core.publisher.Mono<Void> replaceAppSecret(String name, String value) {
				return reactor.core.publisher.Mono.fromRunnable(() -> appSecrets.put(name, value));
			}

			public reactor.core.publisher.Mono<Void> replaceSecrets(String id, String sealed) {
				return reactor.core.publisher.Mono.fromRunnable(() -> connectors.compute(id,
						(k, c) -> new StoredConnector(k, c.status(), c.config(), sealed, c.updatedAt(), c.updatedBy())));
			}
		};
		SecretsRotation rotation = new SecretsRotation(store, rotated);
		assertThat(rotation.reencrypt().block()).isEqualTo(2);
		assertThat(rotation.reencrypt().block()).isZero();
		SecretBox newOnly = new SecretBox(newKey);
		assertThat(newOnly.decrypt(appSecrets.get("signing"))).isEqualTo("token");
		assertThat(newOnly.decrypt(connectors.get("git").encryptedSecrets())).isEqualTo("token");
	}

	@Test
	void anExplicitKeyWinsAndTamperingIsDetected() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		SecretBox box = SecretBox.load(key, dir.resolve("unused.key"));
		assertThat(Files.exists(dir.resolve("unused.key"))).isFalse();
		String sealed = box.encrypt("secret");
		byte[] raw = Base64.getDecoder().decode(sealed);
		raw[raw.length - 1] ^= 1;
		assertThatThrownBy(() -> box.decrypt(Base64.getEncoder().encodeToString(raw)))
				.isInstanceOf(IllegalStateException.class);
		byte[] other = new byte[32];
		other[0] = 1;
		assertThatThrownBy(() -> new SecretBox(other).decrypt(sealed)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecretBox(new byte[16])).hasMessageContaining("32 bytes");
	}
}
