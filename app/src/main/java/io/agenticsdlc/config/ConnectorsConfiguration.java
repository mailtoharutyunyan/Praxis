package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.llm.ModelOverride;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.config.connectors.ConnectorStore;
import io.agenticsdlc.config.connectors.ConnectorTester;
import io.agenticsdlc.config.connectors.SecretBox;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/** Runtime connectors (ADR-0007). */
@Configuration(proxyBeanMethods = false)
class ConnectorsConfiguration {

	@Bean
	SecretBox secretBox(AgenticProperties properties) {
		AgenticProperties.Security security = properties.security();
		return SecretBox.load(security.secretsKey(), security.dataPath().resolve("secrets.key"),
				security.previousSecretsKeys());
	}

	@Bean
	ConnectorSettings connectorSettings(ConnectorStore store, SecretBox box, JsonMapper json, AgenticProperties properties,
			Clock clock) {
		return new ConnectorSettings(store, box, json, properties, clock);
	}

	@Bean
	ConnectorTester connectorTester(WebClient.Builder webClient) {
		return new ConnectorTester(webClient);
	}

	/** The model chosen in the UI (with optional per-role models and prices) replaces {@code agentic.models}. */
	@Bean
	ModelOverride modelOverride(ConnectorSettings settings) {
		return new ModelOverride() {
			@Override
			public long version() {
				return settings.version();
			}

			@Override
			public java.util.Optional<ModelOverride.Choice> current() {
				return settings.model().map(m -> new ModelOverride.Choice(
						new AgenticProperties.Provider(m.provider(), m.apiKey(), m.baseUrl(), m.region(), m.deployment()),
						m.model(), m.roleModels(), m.inputPrice() == null || m.outputPrice() == null ? null
								: new AgenticProperties.Pricing(m.inputPrice(), m.outputPrice(),
										java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
			}
		};
	}

	@Bean
	SlackRuntime slackRuntime(WebClient.Builder webClient, @Value("${agentic.slack.api-url:https://slack.com/api}") String apiUrl,
			ConnectorSettings settings, io.agenticsdlc.core.port.RunStore store,
			io.agenticsdlc.core.intake.TicketSyncStore cursors, Clock clock) {
		return new SlackRuntime(webClient, apiUrl, settings, store, cursors, clock);
	}

	/** Replies in the Slack thread of each run started there, from one instance at a time. */
	@Bean
	PeriodicJob slackUpdatesWatcher(SlackRuntime slack, ClusterConfiguration.JobLeases leases) {
		return new PeriodicJob("Slack thread updates", Duration.ofSeconds(15), Duration.ofMinutes(10),
				() -> slack.current().map(active -> (reactor.core.publisher.Flux<?>) active.updates().sweep())
						.orElse(reactor.core.publisher.Flux.empty()),
				leases.forJob("slack-thread-updates"));
	}

	/** Other instances' changes arrive within this interval. */
	@Bean
	PeriodicJob connectorRefresh(ConnectorSettings settings) {
		return new PeriodicJob("connector settings refresh", Duration.ofSeconds(30), Duration.ofSeconds(30),
				Duration.ofSeconds(30), settings::refresh, null);
	}

	/** Re-encrypts secrets sealed with a previous key (key rotation), then loads the connectors. */
	@EventListener(ApplicationReadyEvent.class)
	void loadConnectors(ApplicationReadyEvent event) {
		var context = event.getApplicationContext();
		new io.agenticsdlc.config.connectors.SecretsRotation(context.getBean(ConnectorStore.class),
				context.getBean(SecretBox.class)).reencrypt().block(Duration.ofSeconds(60));
		context.getBean(ConnectorSettings.class).refresh().block(Duration.ofSeconds(30));
	}
}
