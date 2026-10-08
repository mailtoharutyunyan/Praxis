package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.llm.ModelOverride;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.config.connectors.ConnectorStore;
import io.agenticsdlc.config.connectors.ConnectorTester;
import io.agenticsdlc.config.connectors.LocalAuth;
import io.agenticsdlc.config.connectors.SecretBox;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/** Runtime connectors and built-in sign-in (ADR-0007). */
@Configuration(proxyBeanMethods = false)
class ConnectorsConfiguration {

	@Bean
	SecretBox secretBox(AgenticProperties properties) {
		AgenticProperties.Security security = properties.security();
		return SecretBox.load(security.secretsKey(), security.dataPath().resolve("secrets.key"));
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

	/** The model chosen in the UI applies to every role, over {@code agentic.models}. */
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
						m.model()));
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

	@EventListener(ApplicationReadyEvent.class)
	void loadConnectors(ApplicationReadyEvent event) {
		event.getApplicationContext().getBean(ConnectorSettings.class).refresh().block(Duration.ofSeconds(30));
	}

	@Bean
	@ConditionalOnExpression("'${agentic.security.mode:oidc}' == 'local'")
	LocalAuth localAuth(ConnectorStore store, SecretBox box, Clock clock, AgenticProperties properties,
			@Value("${spring.security.oauth2.resourceserver.jwt.audiences:agentic-sdlc}") List<String> audiences) {
		return new LocalAuth(store, box, clock, properties.security().localTokenTtl(), audiences.getFirst());
	}

	/** In local mode the app checks its own tokens: signature, expiry, issuer and audience. */
	@Bean
	@ConditionalOnExpression("'${agentic.security.mode:oidc}' == 'local'")
	ReactiveJwtDecoder localJwtDecoder(LocalAuth auth,
			@Value("${spring.security.oauth2.resourceserver.jwt.audiences:agentic-sdlc}") List<String> audiences) {
		OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
				aud -> aud != null && aud.stream().anyMatch(audiences::contains));
		OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(LocalAuth.ISSUER), audience);
		// Built on first use, after migrations created the table holding the signing key.
		reactor.core.publisher.Mono<ReactiveJwtDecoder> decoder = auth.publicKey().map(key -> {
			NimbusReactiveJwtDecoder nimbus = NimbusReactiveJwtDecoder.withPublicKey(key).build();
			nimbus.setJwtValidator(validator);
			return (ReactiveJwtDecoder) nimbus;
		}).cacheInvalidateIf(d -> false);
		return token -> decoder.flatMap(d -> d.decode(token));
	}
}
