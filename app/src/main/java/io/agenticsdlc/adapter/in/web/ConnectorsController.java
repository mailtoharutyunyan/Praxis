package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.connectors.ConnectorCatalog;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.config.connectors.ConnectorTester;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Connectors for admins (ADR-0007): their forms, current settings, test and save. Secret values are never returned,
 * only which secrets are set.
 */
@RestController
@RequestMapping("/api/v1/connectors")
class ConnectorsController {

	private final ConnectorSettings settings;
	private final ConnectorTester tester;

	ConnectorsController(ConnectorSettings settings, ConnectorTester tester) {
		this.settings = settings;
		this.tester = tester;
	}

	record ConnectorView(ConnectorCatalog.Definition definition, String status, Map<String, Object> config,
			List<String> secretsSet, Map<String, String> webhooks) {
	}

	record Update(Map<String, Object> config, Map<String, String> secrets) {
	}

	@GetMapping
	List<ConnectorView> list() {
		List<ConnectorView> views = new ArrayList<>();
		for (ConnectorCatalog.Definition d : ConnectorCatalog.ALL) {
			views.add(view(d));
		}
		return views;
	}

	@PutMapping("/{id}")
	Mono<ConnectorView> save(@PathVariable String id, @RequestBody Update update, @AuthenticationPrincipal Jwt user) {
		return settings.save(id, update.config(), update.secrets(), user.getSubject())
				.map(saved -> view(ConnectorCatalog.find(id).orElseThrow()));
	}

	/** Tests the given settings (merged with stored secrets) without saving them. */
	@PostMapping("/{id}/test")
	Mono<ConnectorTester.Result> test(@PathVariable String id, @RequestBody Update update) {
		ConnectorCatalog.find(id).orElseThrow(() -> new IllegalArgumentException("unknown connector " + id));
		Map<String, String> secrets = new LinkedHashMap<>(settings.get(id).map(ConnectorSettings.Connector::secrets)
				.orElse(Map.of()));
		if (update.secrets() != null) {
			update.secrets().forEach((k, v) -> {
				if (v != null && !v.isBlank()) {
					secrets.put(k, v.strip());
				}
			});
		}
		return tester.test(new ConnectorSettings.Connector(id, ConnectorSettings.CONFIGURED,
				update.config() == null ? Map.of() : update.config(), secrets));
	}

	@PostMapping("/{id}/skip")
	Mono<ConnectorView> skip(@PathVariable String id, @AuthenticationPrincipal Jwt user) {
		return settings.skip(id, user.getSubject()).then(Mono.fromSupplier(() -> view(ConnectorCatalog.find(id).orElseThrow())));
	}

	private ConnectorView view(ConnectorCatalog.Definition definition) {
		var stored = settings.get(definition.id());
		String base = settings.publicUrl().isEmpty() ? "<public URL>" : settings.publicUrl();
		Map<String, String> webhooks = switch (definition.id()) {
			case ConnectorCatalog.JIRA -> Map.of("jira", base + "/api/v1/webhooks/jira");
			case ConnectorCatalog.SLACK -> Map.of("slashCommand", base + "/api/v1/webhooks/slack/commands");
			case ConnectorCatalog.WEBHOOKS -> Map.of("github", base + "/api/v1/webhooks/github", "gitlab",
					base + "/api/v1/webhooks/gitlab", "bitbucket", base + "/api/v1/webhooks/bitbucket", "azureDevOps",
					base + "/api/v1/webhooks/azure-devops");
			default -> Map.of();
		};
		return new ConnectorView(definition, stored.map(ConnectorSettings.Connector::status).orElse("PENDING"),
				stored.map(ConnectorSettings.Connector::config).orElse(Map.of()),
				stored.map(c -> List.copyOf(c.secrets().keySet())).orElse(List.of()), webhooks);
	}
}
