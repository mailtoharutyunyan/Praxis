package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.jira.JiraTicketSystem;
import io.agenticsdlc.config.connectors.ConnectorCatalog;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Companion;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.intake.TicketIntake;
import io.agenticsdlc.core.intake.TicketSyncStore;
import io.agenticsdlc.core.intake.TicketUpdates;
import io.agenticsdlc.core.port.RunStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Jira as currently configured: {@code agentic.jira.*} when enabled there, else the Jira connector saved in the UI
 * (ADR-0007), else none. Rebuilt when the connector changes, so Jira can be added or skipped without a restart.
 */
public final class JiraRuntime {

	/** A configured Jira: its client, intake and status updates, and how its webhooks are authenticated. */
	public record Active(io.agenticsdlc.core.intake.TicketSystem tickets, TicketIntake intake, TicketUpdates updates, String webhookSecret,
			String automationToken, String triggerLabel) {
	}

	private record Built(long version, Optional<Active> active) {
	}

	private final WebClient.Builder webClient;
	private final AgenticProperties properties;
	private final ConnectorSettings connectors;
	private final TaskIntake intake;
	private final RunStore store;
	private final TicketSyncStore cursors;
	private final Clock clock;
	private final Optional<Active> fromProperties;
	private volatile Built built = new Built(Long.MIN_VALUE, Optional.empty());

	JiraRuntime(WebClient.Builder webClient, AgenticProperties properties, ConnectorSettings connectors,
			TaskIntake intake, RunStore store, TicketSyncStore cursors, Clock clock) {
		this.webClient = webClient;
		this.properties = properties;
		this.connectors = connectors;
		this.intake = intake;
		this.store = store;
		this.cursors = cursors;
		this.clock = clock;
		// Properties are checked at startup, so a broken configuration fails fast.
		this.fromProperties = properties.jira().enabled() ? Optional.of(fromProperties(properties.jira()))
				: Optional.empty();
	}

	public Optional<Active> current() {
		if (fromProperties.isPresent()) {
			return fromProperties;
		}
		Built snapshot = built;
		long version = connectors.version();
		if (snapshot.version() != version) {
			snapshot = new Built(version, connectors.configured(ConnectorCatalog.JIRA).map(this::fromConnector));
			built = snapshot;
		}
		return snapshot.active();
	}

	private Active fromProperties(AgenticProperties.Jira jira) {
		if (jira.baseUrl().isBlank()) {
			throw new IllegalStateException("agentic.jira.base-url is required when agentic.jira.enabled=true");
		}
		if (jira.webhookSecret().isBlank() && jira.automationToken().isBlank()) {
			throw new IllegalStateException("set agentic.jira.webhook-secret or agentic.jira.automation-token");
		}
		Map<String, TicketIntake.ProjectTarget> projects = new LinkedHashMap<>();
		jira.projects().forEach((key, project) -> projects.put(key, new TicketIntake.ProjectTarget(
				new RepositoryRef(project.kind(), project.cloneUrl()),
				project.baseBranch().isBlank() ? null : project.baseBranch(),
				project.companions().stream().map(c -> {
					RepositoryRef ref = new RepositoryRef(c.kind() == null ? project.kind() : c.kind(), c.cloneUrl());
					return new Companion(c.alias().isBlank() ? Companion.aliasFor(ref) : c.alias(), ref,
							c.baseBranch().isBlank() ? null : c.baseBranch());
				}).toList())));
		return active(jira.deployment(), jira.baseUrl(), jira.email(), jira.apiToken(), jira.webhookSecret(),
				jira.automationToken(), jira.triggerLabel(), projects, connectors.runLinkBase(jira.runLinkBase()));
	}

	private Active fromConnector(ConnectorSettings.Connector c) {
		Map<String, TicketIntake.ProjectTarget> projects = new LinkedHashMap<>();
		for (Map<String, Object> item : c.items("projects")) {
			String baseBranch = String.valueOf(item.getOrDefault("baseBranch", "")).strip();
			projects.put(String.valueOf(item.get("key")).strip(), new TicketIntake.ProjectTarget(
					new RepositoryRef(ScmKind.valueOf(String.valueOf(item.get("kind"))),
							URI.create(String.valueOf(item.get("cloneUrl")).strip())),
					baseBranch.isEmpty() ? null : baseBranch, List.of()));
		}
		return active(c.text("deployment"), c.text("baseUrl"), c.text("email"), c.secret("apiToken"),
				c.secret("webhookSecret"), properties.jira().automationToken(), c.text("triggerLabel"), projects,
				connectors.runLinkBase(properties.jira().runLinkBase()));
	}

	private Active active(String deployment, String baseUrl, String email, String apiToken, String webhookSecret,
			String automationToken, String triggerLabel, Map<String, TicketIntake.ProjectTarget> projects,
			String runLinkBase) {
		JiraTicketSystem tickets = new JiraTicketSystem(webClient, baseUrl, email, apiToken, Duration.ofSeconds(30),
				!"data-center".equals(deployment));
		return new Active(tickets, new TicketIntake(tickets, intake, TaskOrigin.JIRA, triggerLabel, projects),
				new TicketUpdates(store, tickets, cursors, TaskOrigin.JIRA, clock, Duration.ofDays(7), runLinkBase),
				webhookSecret, automationToken, triggerLabel);
	}
}
