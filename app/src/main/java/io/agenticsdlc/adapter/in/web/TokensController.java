package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.identity.ApiTokens;
import io.agenticsdlc.config.identity.IdentityStore.ApiToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Personal API tokens for scripts and AI clients. Each user manages their own; admins can also list and revoke
 * everyone's. Only signed-in users may call this: an API token cannot mint or revoke tokens.
 */
@RestController
@RequestMapping("/api/v1/tokens")
class TokensController {

	private final ApiTokens tokens;

	TokensController(ApiTokens tokens) {
		this.tokens = tokens;
	}

	record NewToken(@NotBlank @Size(max = 100) String name, @NotEmpty List<String> roles,
			@Min(1) @Max(3650) int expiresInDays) {
	}

	/** A token as listed: never its secret. */
	record TokenView(UUID id, String name, String owner, List<String> roles, String hint, Instant createdAt,
			Instant expiresAt, Instant lastUsedAt, Instant revokedAt, boolean active) {

		static TokenView of(ApiToken t) {
			boolean active = t.revokedAt() == null && t.expiresAt().isAfter(Instant.now());
			return new TokenView(t.id(), t.name(), t.owner(), t.roles(), t.hint() + "…", t.createdAt(), t.expiresAt(),
					t.lastUsedAt(), t.revokedAt(), active);
		}
	}

	/** @param token the secret; shown only in this response */
	record CreatedToken(TokenView details, String token) {
	}

	@GetMapping
	Flux<TokenView> list(@RequestParam(defaultValue = "false") boolean all, Authentication caller) {
		Jwt user = session(caller);
		if (all && !roles(caller).contains("admin")) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only admins list everyone's tokens");
		}
		return tokens.list(all ? null : user.getSubject()).map(TokenView::of);
	}

	@PostMapping
	Mono<ResponseEntity<CreatedToken>> create(@Valid @RequestBody NewToken request, Authentication caller) {
		Jwt user = session(caller);
		return tokens.create(user.getSubject(), roles(caller), request.name(), request.roles(),
				Duration.ofDays(request.expiresInDays()))
				.map(created -> ResponseEntity.status(HttpStatus.CREATED)
						.body(new CreatedToken(TokenView.of(created.token()), created.secret())));
	}

	@DeleteMapping("/{id}")
	Mono<ResponseEntity<Void>> revoke(@PathVariable UUID id, Authentication caller) {
		Jwt user = session(caller);
		String owner = roles(caller).contains("admin") ? null : user.getSubject();
		return tokens.revoke(id, owner).map(revoked -> revoked ? ResponseEntity.noContent().<Void>build()
				: ResponseEntity.notFound().<Void>build());
	}

	private static Jwt session(Authentication caller) {
		if (!(caller.getPrincipal() instanceof Jwt jwt)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		SetupController.requireSession(jwt);
		return jwt;
	}

	private static List<String> roles(Authentication caller) {
		return caller.getAuthorities().stream().map(GrantedAuthority::getAuthority)
				.filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5).toLowerCase(Locale.ROOT)).toList();
	}
}
