package io.agenticsdlc.config;

import io.agenticsdlc.config.connectors.ConnectorStore;
import io.agenticsdlc.config.connectors.SecretBox;
import io.agenticsdlc.config.identity.ApiTokens;
import io.agenticsdlc.config.identity.IdentityStore;
import io.agenticsdlc.config.identity.LocalAuth;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/** Built-in sign-in, personal API tokens and request rate limits. */
@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {

	private static final Logger log = LoggerFactory.getLogger(IdentityConfiguration.class);

	@Bean
	ApiTokens apiTokens(IdentityStore identity, Clock clock, AgenticProperties properties) {
		return new ApiTokens(identity, clock, properties.security().apiTokenMaxTtl());
	}

	@Bean
	RateLimitFilter rateLimitFilter(AgenticProperties properties, Clock clock) {
		return new RateLimitFilter(properties.rateLimit(), clock);
	}

	@Bean
	@ConditionalOnExpression("'${agentic.security.mode:oidc}' == 'local'")
	LocalAuth localAuth(IdentityStore identity, ConnectorStore secrets, SecretBox box, Clock clock,
			AgenticProperties properties,
			@Value("${spring.security.oauth2.resourceserver.jwt.audiences:agentic-sdlc}") List<String> audiences) {
		return new LocalAuth(identity, secrets, box, clock, properties.security().localTokenTtl(), audiences.getFirst(),
				properties.security().setupCode());
	}

	/**
	 * In local mode the app checks its own tokens: signature, expiry, issuer and audience, and that the user still
	 * exists and has not signed out (or changed password or roles) since the token was issued.
	 */
	@Bean
	@ConditionalOnExpression("'${agentic.security.mode:oidc}' == 'local'")
	ReactiveJwtDecoder localJwtDecoder(LocalAuth auth,
			@Value("${spring.security.oauth2.resourceserver.jwt.audiences:agentic-sdlc}") List<String> audiences) {
		OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
				aud -> aud != null && aud.stream().anyMatch(audiences::contains));
		OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(LocalAuth.ISSUER), audience);
		// Built on first use, after migrations created the table holding the signing key.
		Mono<ReactiveJwtDecoder> decoder = auth.publicKey().map(key -> {
			NimbusReactiveJwtDecoder nimbus = NimbusReactiveJwtDecoder.withPublicKey(key).build();
			nimbus.setJwtValidator(validator);
			return (ReactiveJwtDecoder) nimbus;
		}).cacheInvalidateIf(d -> false);
		return token -> decoder.flatMap(d -> d.decode(token))
				.flatMap(jwt -> auth.sessionValid(jwt.getSubject(), jwt.getIssuedAt())
						.flatMap(valid -> valid ? Mono.just(jwt)
								: Mono.error(new BadJwtException("the session has ended; sign in again"))));
	}

	/** Other instances' sign-outs and user changes apply here within this interval. */
	@Bean
	@ConditionalOnExpression("'${agentic.security.mode:oidc}' == 'local'")
	PeriodicJob localSessionRefresh(LocalAuth auth) {
		return new PeriodicJob("local session refresh", Duration.ofSeconds(30), Duration.ofSeconds(30),
				Duration.ofSeconds(30), auth::refreshSessions, null);
	}

	/** Until the first admin exists, the setup code that creates it is logged on every start. */
	@EventListener(ApplicationReadyEvent.class)
	void announceSetupCode(ApplicationReadyEvent event) {
		ObjectProvider<LocalAuth> provider = event.getApplicationContext().getBeanProvider(LocalAuth.class);
		LocalAuth auth = provider.getIfAvailable();
		if (auth == null) {
			return;
		}
		auth.adminExists().filter(exists -> !exists).flatMap(none -> auth.setupCode())
				.doOnNext(code -> log.warn("First-run setup code: {} (open the web UI and enter it to create the admin "
						+ "account; it stops working once the admin exists)", code))
				.onErrorResume(e -> {
					log.error("cannot prepare the first-run setup code", e);
					return Mono.empty();
				})
				.block(Duration.ofSeconds(30));
	}
}
