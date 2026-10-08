package io.agenticsdlc.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Stateless JWT resource server. Any OIDC provider works (Keycloak, Entra ID, Okta); configure
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. Roles come from a configurable claim:
 * <ul>
 * <li>{@code viewer}: read runs and events</li>
 * <li>{@code operator}: submit, cancel and resume runs</li>
 * <li>{@code approver}: decide gates and raise risk</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfiguration {

	static final String VIEWER = "VIEWER";
	static final String OPERATOR = "OPERATOR";
	static final String APPROVER = "APPROVER";

	@Bean
	SecurityWebFilterChain apiSecurity(ServerHttpSecurity http, AgenticProperties properties) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.cors(cors -> cors.configurationSource(cors(properties.security().corsAllowedOrigins())))
				.authorizeExchange(auth -> auth
						.pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
						// The web UI's static assets and runtime config are public; its API calls carry the user's JWT.
						.pathMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg", "/ui-config.json")
						.permitAll()
						// Webhooks authenticate by signature or token inside the controller.
						.pathMatchers(HttpMethod.POST, "/api/v1/webhooks/**").permitAll()
						.pathMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(VIEWER, OPERATOR, APPROVER)
						.pathMatchers(HttpMethod.POST, "/api/v1/tasks", "/api/v1/runs/*/cancel", "/api/v1/runs/*/resume")
						.hasRole(OPERATOR)
						.pathMatchers(HttpMethod.POST, "/api/v1/runs/*/decisions", "/api/v1/runs/*/risk")
						.hasRole(APPROVER)
						.anyExchange().denyAll())
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
						rolesConverter(properties.security().rolesClaim()))))
				.headers(headers -> headers
						.contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy(properties.ui().issuer())))
						.referrerPolicy(referrer -> referrer.policy(
								org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER)))
				.build();
	}

	/** Scripts only from this origin; the identity provider is reachable for token calls and silent renew. */
	static String contentSecurityPolicy(String issuer) {
		String idp = issuer == null || issuer.isBlank() ? "" : " " + java.net.URI.create(issuer).resolve("/").toString()
				.replaceAll("/$", "");
		return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
				+ "connect-src 'self'" + idp + "; frame-src 'self'" + idp + "; frame-ancestors 'none'; base-uri 'self'; "
				+ "form-action 'self'" + idp + "; object-src 'none'";
	}

	static Converter<Jwt, Mono<AbstractAuthenticationToken>> rolesConverter(String rolesClaim) {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> roles(jwt.getClaims(), rolesClaim));
		return new ReactiveJwtAuthenticationConverterAdapter(converter);
	}

	/** Reads a list of role names at a dotted claim path, e.g. {@code realm_access.roles}. */
	static Collection<GrantedAuthority> roles(Map<String, Object> claims, String path) {
		Object node = claims;
		for (String segment : path.split("\\.")) {
			if (!(node instanceof Map<?, ?> map)) {
				return List.of();
			}
			node = map.get(segment);
		}
		if (!(node instanceof Collection<?> values)) {
			return List.of();
		}
		return values.stream()
				.map(String::valueOf)
				.map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
				.toList();
	}

	private static CorsConfigurationSource cors(List<String> allowedOrigins) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID"));
		config.setExposedHeaders(List.of("Location"));
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}
}
