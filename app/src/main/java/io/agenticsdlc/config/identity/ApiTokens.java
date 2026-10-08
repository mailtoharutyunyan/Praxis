package io.agenticsdlc.config.identity;

import io.agenticsdlc.config.identity.IdentityStore.ApiToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Personal API tokens: long random secrets for scripts and AI clients (Claude Code, Codex, Gemini CLI over MCP).
 * <ul>
 * <li>Only a SHA-256 of each token is stored; the token is shown once, when created.</li>
 * <li>A token carries some of its owner's roles, never {@code admin}: it can work with runs, not change settings or
 * mint more tokens.</li>
 * <li>Every token expires (at most {@code maxTtl}) and can be revoked; deleting a user revokes theirs.</li>
 * </ul>
 * An authenticated token becomes a {@link Jwt} with the owner as subject and a {@code token_id} claim, so every rule
 * written for signed-in users applies unchanged.
 */
public final class ApiTokens {

	public static final String PREFIX = "asdlc_";
	public static final String TOKEN_ID_CLAIM = "token_id";
	public static final List<String> GRANTABLE_ROLES = List.of("viewer", "operator", "approver");
	private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

	/** @param secret the token itself; shown once and never retrievable again */
	public record Created(ApiToken token, String secret) {
	}

	private final IdentityStore identity;
	private final Clock clock;
	private final Duration maxTtl;
	private final SecureRandom random = new SecureRandom();
	private final Map<UUID, Instant> lastTouched = new ConcurrentHashMap<>();

	public ApiTokens(IdentityStore identity, Clock clock, Duration maxTtl) {
		this.identity = identity;
		this.clock = clock;
		this.maxTtl = maxTtl;
	}

	public static boolean looksLikeToken(String bearer) {
		return bearer != null && bearer.startsWith(PREFIX);
	}

	/**
	 * @param ownerRoles the roles the owner has now; the token gets the requested ones among them
	 * @param ttl how long the token lives, at most {@code maxTtl}
	 */
	public Mono<Created> create(String owner, List<String> ownerRoles, String name, List<String> roles, Duration ttl) {
		String label = name == null ? "" : name.strip();
		if (label.isEmpty() || label.length() > 100) {
			return Mono.error(new IllegalArgumentException("give the token a name of 1-100 characters"));
		}
		List<String> wanted = roles == null ? List.of() : roles.stream().map(r -> r.strip().toLowerCase(Locale.ROOT))
				.collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
		List<String> owned = ownerRoles.stream().map(r -> r.toLowerCase(Locale.ROOT)).toList();
		if (wanted.isEmpty() || !GRANTABLE_ROLES.containsAll(wanted) || !owned.containsAll(wanted)) {
			return Mono.error(new IllegalArgumentException("a token can have some of your roles among " + GRANTABLE_ROLES));
		}
		if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxTtl) > 0) {
			return Mono.error(new IllegalArgumentException("a token lives at most " + maxTtl.toDays() + " days"));
		}
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = clock.instant();
		ApiToken token = new ApiToken(UUID.randomUUID(), label, owner, wanted, sha256(secret),
				secret.substring(0, PREFIX.length() + 4), now, now.plus(ttl), null, null);
		return identity.createToken(token).thenReturn(new Created(token, secret));
	}

	/** The token as a principal; empty when it is unknown, expired or revoked. */
	public Mono<Jwt> authenticate(String secret) {
		if (!looksLikeToken(secret)) {
			return Mono.empty();
		}
		Instant now = clock.instant();
		return identity.tokenByHash(sha256(secret))
				.filter(t -> t.revokedAt() == null && t.expiresAt().isAfter(now))
				.flatMap(t -> touch(t, now).thenReturn(Jwt.withTokenValue(secret)
						.header("typ", "api-token")
						.subject(t.owner())
						.claim("roles", t.roles())
						.claim("preferred_username", t.owner())
						.claim(TOKEN_ID_CLAIM, t.id().toString())
						.issuedAt(t.createdAt())
						.expiresAt(t.expiresAt())
						.build()));
	}

	/** Records use at most every few minutes, so busy clients do not write on every request. */
	private Mono<Void> touch(ApiToken token, Instant now) {
		Instant last = lastTouched.get(token.id());
		if (last != null && last.plus(TOUCH_INTERVAL).isAfter(now)) {
			return Mono.empty();
		}
		lastTouched.put(token.id(), now);
		return identity.touchToken(token.id(), now).onErrorResume(e -> Mono.empty());
	}

	public Flux<ApiToken> list(String owner) {
		return identity.tokens(owner);
	}

	/** @param owner null to revoke anyone's token (admins) */
	public Mono<Boolean> revoke(UUID id, String owner) {
		return identity.revokeToken(id, owner, clock.instant());
	}

	static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
