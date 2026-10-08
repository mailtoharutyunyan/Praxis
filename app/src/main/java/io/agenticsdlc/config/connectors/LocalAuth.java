package io.agenticsdlc.config.connectors;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Built-in sign-in for installs without an identity provider (ADR-0007): usernames with BCrypt passwords, and RS256
 * tokens from a key pair the app generates once and keeps encrypted. The resource server checks these tokens like
 * any other (issuer, audience, expiry), so roles and API rules are unchanged.
 */
public final class LocalAuth {

	public static final String ISSUER = "agentic-sdlc-local";
	public static final List<String> ALL_ROLES = List.of("viewer", "operator", "approver", "admin");
	private static final String KEY_NAME = "local-signing-key";
	private static final int MAX_FAILURES = 5;
	private static final Duration LOCKOUT = Duration.ofMinutes(1);

	public record Token(String token, Instant expiresAt, String username, List<String> roles) {
	}

	private record Failures(int count, Instant lockedUntil) {
	}

	private final ConnectorStore store;
	private final SecretBox box;
	private final Clock clock;
	private final Duration ttl;
	private final String audience;
	private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
	private final Map<String, Failures> failures = new ConcurrentHashMap<>();
	/** Loaded once on first use (the table holding it exists only after migrations); failures are not cached. */
	private final Mono<KeyPair> keys = Mono.defer(this::loadOrCreateKeys).cacheInvalidateIf(pair -> false);

	public LocalAuth(ConnectorStore store, SecretBox box, Clock clock, Duration ttl, String audience) {
		this.store = store;
		this.box = box;
		this.clock = clock;
		this.ttl = ttl;
		this.audience = audience;
	}

	/** The key that verifies local tokens; loaded on first use, once migrations created its table. */
	public Mono<RSAPublicKey> publicKey() {
		return keys.map(pair -> (RSAPublicKey) pair.getPublic());
	}

	/** Creates the first user, with every role; fails once any user exists. */
	public Mono<Token> createFirstAdmin(String username, String password) {
		String name = validUsername(username);
		if (password == null || password.length() < 10) {
			return Mono.error(new IllegalArgumentException("the password needs at least 10 characters"));
		}
		return store.userCount().flatMap(count -> {
			if (count > 0) {
				return Mono.error(new IllegalStateException("the admin account already exists; sign in instead"));
			}
			// BCrypt is deliberately slow: keep it off the event loop.
			return Mono.fromCallable(() -> passwords.encode(password)).subscribeOn(Schedulers.boundedElastic())
					.flatMap(hash -> store.createUser(new ConnectorStore.LocalUser(name, hash, ALL_ROLES, clock.instant())))
					.flatMap(created -> created ? issue(name, ALL_ROLES)
							: Mono.error(new IllegalStateException("the admin account already exists")));
		});
	}

	/** Signs in; repeated failures lock the username for a minute. Emits empty for wrong credentials. */
	public Mono<Token> signIn(String username, String password) {
		String name = username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
		Failures failed = failures.get(name);
		if (failed != null && failed.lockedUntil() != null && failed.lockedUntil().isAfter(clock.instant())) {
			return Mono.error(new IllegalStateException("too many failed sign-ins; try again in a minute"));
		}
		return store.user(name)
				.filterWhen(user -> Mono.fromCallable(() -> password != null && passwords.matches(password,
						user.passwordHash())).subscribeOn(Schedulers.boundedElastic()))
				.flatMap(user -> {
					failures.remove(name);
					return issue(user.username(), user.roles());
				})
				.switchIfEmpty(Mono.fromRunnable(() -> failures.merge(name, new Failures(1, null), (old, one) -> {
					int count = old.count() + 1;
					return new Failures(count, count >= MAX_FAILURES ? clock.instant().plus(LOCKOUT) : null);
				})));
	}

	private Mono<Token> issue(String username, List<String> roles) {
		return keys.map(pair -> {
			Instant now = clock.instant();
			Instant expires = now.plus(ttl);
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.issuer(ISSUER).subject(username).audience(audience).jwtID(UUID.randomUUID().toString())
					.issueTime(Date.from(now)).expirationTime(Date.from(expires))
					.claim("roles", roles).claim("preferred_username", username)
					.build();
			try {
				SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_NAME).build(), claims);
				jwt.sign(new RSASSASigner(pair.getPrivate()));
				return new Token(jwt.serialize(), expires, username, roles);
			}
			catch (JOSEException e) {
				throw new IllegalStateException("cannot sign the token", e);
			}
		});
	}

	private Mono<KeyPair> loadOrCreateKeys() {
		return Mono.fromCallable(() -> {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair fresh = generator.generateKeyPair();
			return box.encrypt(Base64.getEncoder().encodeToString(fresh.getPrivate().getEncoded()));
		}).subscribeOn(Schedulers.boundedElastic())
				// Every instance converges on the first stored key.
				.flatMap(candidate -> store.appSecretIfAbsent(KEY_NAME, candidate))
				.map(stored -> {
					try {
						byte[] der = Base64.getDecoder().decode(box.decrypt(stored));
						KeyFactory factory = KeyFactory.getInstance("RSA");
						RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
						var crt = (java.security.interfaces.RSAPrivateCrtKey) privateKey;
						RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
								new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
						return new KeyPair(publicKey, privateKey);
					}
					catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
						throw new IllegalStateException("cannot load the local signing key", e);
					}
				});
	}

	private static String validUsername(String username) {
		String name = username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
		if (!name.matches("[a-z0-9][a-z0-9._@-]{1,99}")) {
			throw new IllegalArgumentException("usernames are 2-100 letters, digits, dots, dashes, underscores or @");
		}
		return name;
	}
}
