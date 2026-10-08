package io.agenticsdlc.config.connectors;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * After a key rotation ({@code AGENTIC_SECRETS_KEY} new, the old one in {@code AGENTIC_SECRETS_KEY_PREVIOUS}),
 * re-encrypts every stored secret with the current key. Idempotent; safe on every instance and every start. Once it
 * has run, the previous key can be removed.
 */
public final class SecretsRotation {

	private static final Logger log = LoggerFactory.getLogger(SecretsRotation.class);

	private final ConnectorStore store;
	private final SecretBox box;

	public SecretsRotation(ConnectorStore store, SecretBox box) {
		this.store = store;
		this.box = box;
	}

	/** Emits how many secrets were re-encrypted. */
	public Mono<Integer> reencrypt() {
		AtomicInteger count = new AtomicInteger();
		Mono<Void> connectors = store.connectors()
				.filter(c -> c.encryptedSecrets() != null && !box.sealedWithCurrentKey(c.encryptedSecrets()))
				.concatMap(c -> store.replaceSecrets(c.id(), box.encrypt(box.decrypt(c.encryptedSecrets())))
						.doOnSuccess(v -> count.incrementAndGet()))
				.then();
		Mono<Void> appSecrets = store.appSecrets().flatMapMany(all -> Flux.fromIterable(all.entrySet()))
				.filter(e -> !box.sealedWithCurrentKey(e.getValue()))
				.concatMap(e -> store.replaceAppSecret(e.getKey(), box.encrypt(box.decrypt(e.getValue())))
						.doOnSuccess(v -> count.incrementAndGet()))
				.then();
		return connectors.then(appSecrets).then(Mono.fromSupplier(count::get))
				.doOnNext(n -> {
					if (n > 0) {
						log.warn("re-encrypted {} stored secrets with the current key; AGENTIC_SECRETS_KEY_PREVIOUS can be "
								+ "removed now", n);
					}
				});
	}
}
