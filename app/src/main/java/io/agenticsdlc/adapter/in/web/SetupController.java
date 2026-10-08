package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.connectors.ConnectorCatalog;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.config.connectors.ConnectorStore;
import io.agenticsdlc.config.connectors.LocalAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
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
	private final ConnectorStore store;
	private final ObjectProvider<LocalAuth> localAuth;
	private final AgenticProperties properties;

	SetupController(ConnectorSettings settings, ConnectorStore store, ObjectProvider<LocalAuth> localAuth,
			AgenticProperties properties) {
		this.settings = settings;
		this.store = store;
		this.localAuth = localAuth;
		this.properties = properties;
	}

	record Step(String id, String title, boolean required, String state) {
	}

	record Status(String authMode, boolean complete, List<Step> steps) {
	}

	record Credentials(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
	}

	record SignedIn(String token, Instant expiresAt, String username, List<String> roles) {
	}

	@GetMapping("/setup")
	Mono<Status> status() {
		boolean local = properties.security().local();
		Mono<Long> users = local ? store.userCount() : Mono.just(1L);
		return users.map(count -> {
			List<Step> steps = new ArrayList<>();
			if (local) {
				steps.add(new Step("admin", "Admin account", true, count > 0 ? "DONE" : "PENDING"));
			}
			for (ConnectorCatalog.Definition d : ConnectorCatalog.ALL) {
				steps.add(new Step(d.id(), d.title(), d.required(), state(d.id())));
			}
			boolean complete = steps.stream().allMatch(s -> s.required() ? s.state().equals("DONE")
					: !s.state().equals("PENDING"));
			return new Status(local ? "local" : properties.ui().authMode(), complete, steps);
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
					|| !properties.scm().feedback().gitlabToken().isBlank()
					|| !properties.scm().feedback().bitbucketSecret().isBlank()
					|| !properties.scm().feedback().azureDevOpsSecret().isBlank();
			default -> false;
		};
		return fromProperties ? "DONE" : "PENDING";
	}

	/** Creates the first admin (local sign-in only, and only while no user exists) and signs them in. */
	@PostMapping("/setup/admin")
	Mono<SignedIn> createAdmin(@Valid @RequestBody Credentials credentials) {
		LocalAuth auth = requireLocal();
		return auth.createFirstAdmin(credentials.username(), credentials.password())
				.map(t -> new SignedIn(t.token(), t.expiresAt(), t.username(), t.roles()));
	}

	@PostMapping("/auth/login")
	Mono<SignedIn> login(@Valid @RequestBody Credentials credentials) {
		return requireLocal().signIn(credentials.username(), credentials.password())
				.onErrorMap(IllegalStateException.class,
						e -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()))
				.map(t -> new SignedIn(t.token(), t.expiresAt(), t.username(), t.roles()))
				.switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
						"wrong username or password")));
	}

	private LocalAuth requireLocal() {
		LocalAuth auth = localAuth.getIfAvailable();
		if (auth == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sign-in is handled by the identity provider");
		}
		return auth;
	}
}
