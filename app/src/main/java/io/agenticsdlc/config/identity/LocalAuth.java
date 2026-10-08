package io.agenticsdlc.config.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.agenticsdlc.config.connectors.ConnectorStore;
import io.agenticsdlc.config.connectors.SecretBox;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Built-in sign-in for installs without an identity provider (ADR-0007): usernames with BCrypt passwords, and RS256
 * tokens from a key pair the app generates once and keeps encrypted. The resource server checks these tokens like
 * any other (issuer, audience, expiry), plus whether the user still exists and has not signed out since.
 * <ul>
 * <li>The first admin can only be created with the one-time setup code from the application log, so whoever reaches
 * a fresh install first cannot claim it.</li>
 * <li>Five failed sign-ins lock an account for a minute; the count lives in the database, shared by instances.</li>
 * <li>Signing out, a password change, a role change or deletion end every session issued before.</li>
 * </ul>
 */
public final class LocalAuth {

	public static final String ISSUER = "agentic-sdlc-local";
	public static final List<String> ALL_ROLES = List.of("viewer", "operator", "approver", "admin");
	public static final int MIN_PASSWORD = 10;
	private static final String KEY_NAME = "local-signing-key";
	private static final String SETUP_CODE_NAME = "setup-code";
	private static final int MAX_FAILURES = 5;
	private static final Duration LOCKOUT = Duration.ofMinutes(1);
	/** Unambiguous characters for codes read off a log and typed by hand. */
	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	public record Token(String token, Instant expiresAt, String username, List<String> roles) {
	}

	public record Account(String username, List<String> roles, Instant createdAt, boolean locked) {
	}

	private final IdentityStore identity;
	private final ConnectorStore secrets;
	private final SecretBox box;
	private final Clock clock;
	private final Duration ttl;
	private final String audience;
	private final String presetSetupCode;
	private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
	/** Compared against when the username is unknown, so a miss takes as long as a wrong password. */
	private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
	/** Per user: tokens issued before this instant are rejected. Refreshed from the database periodically. */
	private final Map<String, Instant> sessionFloors = new ConcurrentHashMap<>();
	/** Loaded once on first use (the table holding it exists only after migrations); failures are not cached. */
	private final Mono<KeyPair> keys = Mono.defer(this::loadOrCreateKeys).cacheInvalidateIf(pair -> false);

	/** @param presetSetupCode the first-run setup code to use instead of a generated one; empty to generate */
	public LocalAuth(IdentityStore identity, ConnectorStore secrets, SecretBox box, Clock clock, Duration ttl,
			String audience, String presetSetupCode) {
		this.identity = identity;
		this.secrets = secrets;
		this.box = box;
		this.clock = clock;
		this.ttl = ttl;
		this.audience = audience;
		this.presetSetupCode = presetSetupCode == null ? "" : presetSetupCode.strip();
	}

	/** The key that verifies local tokens; loaded on first use, once migrations created its table. */
	public Mono<RSAPublicKey> publicKey() {
		return keys.map(pair -> (RSAPublicKey) pair.getPublic());
	}

	public Mono<Boolean> adminExists() {
		return identity.userCount().map(count -> count > 0);
	}

	/** The one-time code that creates the first admin; every instance converges on the first one stored. */
	public Mono<String> setupCode() {
		String candidate = presetSetupCode.isEmpty() ? randomCode() : presetSetupCode;
		return secrets.appSecretIfAbsent(SETUP_CODE_NAME, box.encrypt(candidate)).map(box::decrypt);
	}

	private static String randomCode() {
		SecureRandom random = new SecureRandom();
		StringBuilder code = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			if (i > 0 && i % 4 == 0) {
				code.append('-');
			}
			code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
		}
		return code.toString();
	}

	/** Creates the first user, with every role; needs the setup code and fails once any user exists. */
	public Mono<Token> createFirstAdmin(String setupCode, String username, String password) {
		String name = validUsername(username);
		validPassword(password);
		return identity.userCount().flatMap(count -> {
			if (count > 0) {
				return Mono.error(new IllegalStateException("the admin account already exists; sign in instead"));
			}
			return setupCode().flatMap(expected -> sameCode(expected, setupCode) ? hash(password)
					: Mono.error(new IllegalArgumentException("wrong setup code; it is printed in the application log")))
					.flatMap(hash -> identity.createUser(name, hash, ALL_ROLES, clock.instant()))
					.flatMap(created -> created ? issue(name, ALL_ROLES, null)
							: Mono.error(new IllegalStateException("the admin account already exists")));
		});
	}

	private static boolean sameCode(String expected, String given) {
		String a = expected.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		String b = given == null ? "" : given.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	/** Signs in; emits empty for wrong credentials and errors while the account is locked. */
	public Mono<Token> signIn(String username, String password) {
		String name = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
		return identity.user(name)
				.flatMap(user -> {
					if (user.lockedUntil() != null && user.lockedUntil().isAfter(clock.instant())) {
						return Mono.error(new IllegalStateException("too many failed sign-ins; try again in a minute"));
					}
					return matches(password, user.passwordHash()).flatMap(ok -> ok
							? identity.recordSuccess(name).then(issue(user.username(), user.roles(), floorOf(user)))
							: identity.recordFailure(name, clock.instant(), MAX_FAILURES, LOCKOUT).then(Mono.empty()));
				})
				.switchIfEmpty(Mono.defer(() -> matches(password, dummyHash).then(Mono.empty())));
	}

	/** Changes the caller's password after checking the current one; ends other sessions and returns a new one. */
	public Mono<Token> changePassword(String username, String current, String next) {
		validPassword(next);
		return identity.user(username)
				.switchIfEmpty(Mono.error(new IllegalArgumentException("no such user")))
				.flatMap(user -> matches(current, user.passwordHash()).flatMap(ok -> ok
						? hash(next).flatMap(hash -> {
							Instant floor = nextFloor(username);
							return identity.updatePassword(username, hash, floor)
									.then(Mono.fromRunnable(() -> sessionFloors.put(username, floor)))
									.then(issue(username, user.roles(), floor));
						})
						: Mono.error(new IllegalArgumentException("the current password is wrong"))));
	}

	/** Ends every session of the user, on every device. */
	public Mono<Void> signOut(String username) {
		Instant floor = nextFloor(username);
		return identity.endSessions(username, floor).then(Mono.fromRunnable(() -> sessionFloors.put(username, floor)));
	}

	// Administration

	public Flux<Account> accounts() {
		Instant now = clock.instant();
		return identity.users().map(u -> new Account(u.username(), u.roles(), u.createdAt(),
				u.lockedUntil() != null && u.lockedUntil().isAfter(now)));
	}

	public Mono<Account> createUser(String username, String password, List<String> roles) {
		String name = validUsername(username);
		validPassword(password);
		List<String> valid = validRoles(roles);
		return hash(password).flatMap(hash -> identity.createUser(name, hash, valid, clock.instant()))
				.flatMap(created -> created ? Mono.just(new Account(name, valid, clock.instant(), false))
						: Mono.error(new IllegalStateException("user " + name + " already exists")));
	}

	public Mono<Void> setRoles(String actor, String username, List<String> roles) {
		List<String> valid = validRoles(roles);
		Mono<Void> guard = valid.contains("admin") ? Mono.empty() : keepsAnAdmin(username, "remove admin from");
		Instant floor = nextFloor(username);
		return guard.then(identity.updateRoles(username, valid, floor))
				.flatMap(updated -> updated ? Mono.fromRunnable(() -> sessionFloors.put(username, floor)).then()
						: Mono.error(new IllegalArgumentException("no such user " + username)));
	}

	public Mono<Void> resetPassword(String username, String password) {
		validPassword(password);
		Instant floor = nextFloor(username);
		return hash(password).flatMap(hash -> identity.updatePassword(username, hash, floor))
				.flatMap(updated -> updated ? Mono.fromRunnable(() -> sessionFloors.put(username, floor)).then()
						: Mono.error(new IllegalArgumentException("no such user " + username)));
	}

	/** Deletes a user and revokes their API tokens; never yourself or the last admin. */
	public Mono<Void> deleteUser(String actor, String username) {
		if (username.equals(actor)) {
			return Mono.error(new IllegalArgumentException("you cannot delete your own account"));
		}
		return keepsAnAdmin(username, "delete")
				.then(identity.deleteUser(username))
				.flatMap(deleted -> deleted ? identity.revokeTokensOf(username, clock.instant())
						.then(Mono.fromRunnable(() -> sessionFloors.remove(username))).then()
						: Mono.error(new IllegalArgumentException("no such user " + username)));
	}

	private Mono<Void> keepsAnAdmin(String username, String action) {
		return identity.users().filter(u -> u.roles().contains("admin")).map(IdentityStore.LocalUser::username)
				.collectList().flatMap(admins -> admins.size() == 1 && admins.contains(username)
						? Mono.error(new IllegalStateException("cannot " + action + " the last admin"))
						: Mono.empty());
	}

	// Sessions

	/** Whether a token issued at {@code issuedAt} for {@code username} is still a valid session. */
	public Mono<Boolean> sessionValid(String username, Instant issuedAt) {
		if (username == null || issuedAt == null) {
			return Mono.just(false);
		}
		Instant floor = sessionFloors.get(username);
		Mono<Instant> known = floor != null ? Mono.just(floor)
				: identity.user(username).map(u -> {
					sessionFloors.put(username, u.sessionsValidAfter());
					return u.sessionsValidAfter();
				});
		return known.map(f -> !issuedAt.isBefore(f)).defaultIfEmpty(false);
	}

	/** Picks up other instances' sign-outs and deletions. */
	public Mono<Void> refreshSessions() {
		return identity.users().collectMap(IdentityStore.LocalUser::username, IdentityStore.LocalUser::sessionsValidAfter)
				.doOnNext(fresh -> {
					sessionFloors.keySet().retainAll(fresh.keySet());
					sessionFloors.putAll(fresh);
				}).then();
	}

	/** A sign-in right after a sign-out (same second) must still be issued at or after the cut-off. */
	private Instant floorOf(IdentityStore.LocalUser user) {
		Instant cached = sessionFloors.get(user.username());
		return cached != null && cached.isAfter(user.sessionsValidAfter()) ? cached : user.sessionsValidAfter();
	}

	/**
	 * Token issue times have one-second precision: every token up to now falls below the next whole second. Tokens can
	 * be issued at the current cut-off (a sign-in right after a sign-out), so a new cut-off also moves past it.
	 */
	private Instant nextFloor(String username) {
		Instant next = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
		Instant current = sessionFloors.get(username);
		return current != null && !current.isBefore(next) ? current.plusSeconds(1) : next;
	}

	private Mono<Token> issue(String username, List<String> roles, Instant notBefore) {
		return keys.map(pair -> {
			Instant now = clock.instant();
			Instant issued = notBefore != null && notBefore.isAfter(now) ? notBefore : now;
			Instant expires = issued.plus(ttl);
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.issuer(ISSUER).subject(username).audience(audience).jwtID(UUID.randomUUID().toString())
					.issueTime(Date.from(issued)).expirationTime(Date.from(expires))
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

	// BCrypt is deliberately slow: keep it off the event loop.
	private Mono<String> hash(String password) {
		return Mono.fromCallable(() -> passwords.encode(password)).subscribeOn(Schedulers.boundedElastic());
	}

	private Mono<Boolean> matches(String password, String hash) {
		return Mono.fromCallable(() -> password != null && passwords.matches(password, hash))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private Mono<KeyPair> loadOrCreateKeys() {
		return Mono.fromCallable(() -> {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair fresh = generator.generateKeyPair();
			return box.encrypt(Base64.getEncoder().encodeToString(fresh.getPrivate().getEncoded()));
		}).subscribeOn(Schedulers.boundedElastic())
				// Every instance converges on the first stored key.
				.flatMap(candidate -> secrets.appSecretIfAbsent(KEY_NAME, candidate))
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

	static String validUsername(String username) {
		String name = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
		if (!name.matches("[a-z0-9][a-z0-9._@-]{1,99}")) {
			throw new IllegalArgumentException("usernames are 2-100 letters, digits, dots, dashes, underscores or @");
		}
		return name;
	}

	private static void validPassword(String password) {
		if (password == null || password.length() < MIN_PASSWORD) {
			throw new IllegalArgumentException("the password needs at least " + MIN_PASSWORD + " characters");
		}
	}

	private static List<String> validRoles(List<String> roles) {
		List<String> valid = roles == null ? List.of() : roles.stream().map(r -> r.strip().toLowerCase(Locale.ROOT))
				.collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
		if (valid.isEmpty() || !ALL_ROLES.containsAll(valid)) {
			throw new IllegalArgumentException("roles must be some of " + ALL_ROLES);
		}
		return valid;
	}
}
