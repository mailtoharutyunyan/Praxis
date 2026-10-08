package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.connectors.ConnectorCatalog;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.config.identity.ApiTokens;
import io.agenticsdlc.config.identity.LocalAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * First-run setup and local sign-in (ADR-0007). The status is public and holds no secrets: which steps are done,
 * so the UI shows the onboarding wizard until the required ones are and the optional ones are done or skipped.
 */
@RestController
@RequestMapping("/api/v1")
class SetupController {

	private final ConnectorSettings settings;
	private final ObjectProvider<LocalAuth> localAuth;
	private final AgenticProperties properties;

	SetupController(ConnectorSettings settings, ObjectProvider<LocalAuth> localAuth, AgenticProperties properties) {
		this.settings = settings;
		this.localAuth = localAuth;
		this.properties = properties;
	}

	record Step(String id, String title, boolean required, String state) {
	}

	/** @param setupCodeRequired creating the first admin needs the code from the application log */
	record Status(String authMode, boolean complete, boolean setupCodeRequired, List<Step> steps) {
	}

	record Credentials(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
	}

	record FirstAdmin(@NotBlank @Size(max = 40) String setupCode, @NotBlank @Size(max = 100) String username,
			@NotBlank @Size(max = 200) String password) {
	}

	record PasswordChange(@NotBlank @Size(max = 200) String currentPassword,
			@NotBlank @Size(max = 200) String newPassword) {
	}

	record SignedIn(String token, Instant expiresAt, String username, List<String> roles) {
	}

	@GetMapping("/setup")
	Mono<Status> status() {
		boolean local = properties.security().local();
		LocalAuth auth = localAuth.getIfAvailable();
		Mono<Boolean> admin = local && auth != null ? auth.adminExists() : Mono.just(true);
		return admin.map(exists -> {
			List<Step> steps = new ArrayList<>();
			if (local) {
				steps.add(new Step("admin", "Admin account", true, exists ? "DONE" : "PENDING"));
			}
			for (ConnectorCatalog.Definition d : ConnectorCatalog.ALL) {
				steps.add(new Step(d.id(), d.title(), d.required(), state(d.id())));
			}
			boolean complete = steps.stream().allMatch(s -> s.required() ? s.state().equals("DONE")
					: !s.state().equals("PENDING"));
			return new Status(local ? "local" : properties.ui().authMode(), complete, local && !exists, steps);
		});
	}

	/** DONE when configured in the UI or already through properties; SKIPPED or PENDING otherwise. */
	private String state(String id) {
		var stored = settings.get(id);
		if (stored.isPresent()) {
			return ConnectorSettings.SKIPPED.equals(stored.get().status()) ? "SKIPPED" : "DONE";
		}
		boolean fromProperties = switch (id) {
			case ConnectorCatalog.APP -> !properties.mcp().runLinkBase().isBlank() || !properties.jira().runLinkBase().isBlank();
			case ConnectorCatalog.GIT -> properties.scm().tokens().values().stream().anyMatch(t -> t != null && !t.isBlank());
			case ConnectorCatalog.MODELS -> properties.models().providers().values().stream()
					.anyMatch(p -> !p.apiKey().isBlank() || p.type().equals("bedrock"));
			case ConnectorCatalog.JIRA -> properties.jira().enabled();
			case ConnectorCatalog.WEBHOOKS -> !properties.scm().feedback().githubSecret().isBlank()
					|| !properties.scm().feedback().gitlabToken().isBlank();
			default -> false;
		};
		return fromProperties ? "DONE" : "PENDING";
	}

	/**
	 * Creates the first admin (built-in sign-in only, and only while no user exists) and signs them in. Needs the
	 * one-time setup code from the application log, so whoever reaches a fresh install first cannot claim it.
	 */
	@PostMapping("/setup/admin")
	Mono<SignedIn> createAdmin(@Valid @RequestBody FirstAdmin request) {
		LocalAuth auth = requireLocal();
		return auth.createFirstAdmin(request.setupCode(), request.username(), request.password()).map(SetupController::signedIn);
	}

	@PostMapping("/auth/login")
	Mono<SignedIn> login(@Valid @RequestBody Credentials credentials) {
		return requireLocal().signIn(credentials.username(), credentials.password())
				.onErrorMap(IllegalStateException.class,
						e -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()))
				.map(SetupController::signedIn)
				.switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
						"wrong username or password")));
	}

	/** Ends every session of the caller, on every device. */
	@PostMapping("/auth/logout")
	Mono<ResponseEntity<Void>> logout(@AuthenticationPrincipal Jwt user) {
		LocalAuth auth = requireLocal();
		requireSession(user);
		return auth.signOut(user.getSubject()).thenReturn(ResponseEntity.noContent().build());
	}

	/** Changes the caller's password; other sessions end and a new token is returned for this one. */
	@PostMapping("/auth/password")
	Mono<SignedIn> changePassword(@Valid @RequestBody PasswordChange change, @AuthenticationPrincipal Jwt user) {
		LocalAuth auth = requireLocal();
		requireSession(user);
		return auth.changePassword(user.getSubject(), change.currentPassword(), change.newPassword())
				.map(SetupController::signedIn);
	}

	static void requireSession(Jwt user) {
		if (user.hasClaim(ApiTokens.TOKEN_ID_CLAIM)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "sign in to do this; API tokens cannot");
		}
	}

	private static SignedIn signedIn(LocalAuth.Token t) {
		return new SignedIn(t.token(), t.expiresAt(), t.username(), t.roles());
	}

	private LocalAuth requireLocal() {
		LocalAuth auth = localAuth.getIfAvailable();
		if (auth == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sign-in is handled by the identity provider");
		}
		return auth;
	}
}
