package io.agenticsdlc.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.agenticsdlc.adapter.in.mcp.ProtectedResourceMetadata;
import io.agenticsdlc.config.identity.ApiTokens;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
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
	static final String ADMIN = "ADMIN";

	@Bean
	SecurityWebFilterChain apiSecurity(ServerHttpSecurity http, AgenticProperties properties, ApiTokens apiTokens,
			ObjectProvider<ReactiveJwtDecoder> decoders,
			@Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.cors(cors -> cors.configurationSource(cors(properties.security().corsAllowedOrigins(), mcpEndpoint)))
				.authorizeExchange(auth -> auth
						.pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
						// The web UI's static assets and runtime config are public; its API calls carry the user's JWT.
						.pathMatchers(HttpMethod.GET, "/", "/index.html", "/silent-renew.html", "/assets/**", "/favicon.ico",
								"/favicon.svg", "/ui-config.json")
						.permitAll()
						// Webhooks authenticate by signature or token inside the controller.
						.pathMatchers(HttpMethod.POST, "/api/v1/webhooks/**").permitAll()
						// First-run setup status and local sign-in (ADR-0007); they hold no secrets.
						.pathMatchers(HttpMethod.GET, "/api/v1/setup").permitAll()
						.pathMatchers(HttpMethod.POST, "/api/v1/setup/admin", "/api/v1/auth/login").permitAll()
						.pathMatchers("/api/v1/connectors/**", "/api/v1/connectors").hasRole(ADMIN)
						.pathMatchers("/api/v1/users/**", "/api/v1/users").hasRole(ADMIN)
						// Any signed-in user manages their own session and API tokens (the controllers refuse API tokens).
						.pathMatchers(HttpMethod.POST, "/api/v1/auth/logout", "/api/v1/auth/password").authenticated()
						.pathMatchers("/api/v1/tokens/**", "/api/v1/tokens").authenticated()
						// MCP clients discover the authorization server here (RFC 9728).
						.pathMatchers(HttpMethod.GET, ProtectedResourceMetadata.PATH, ProtectedResourceMetadata.PATH + "/**")
						.permitAll()
						// MCP: any API role may connect; each tool checks the role it needs (ADR-0005).
						.pathMatchers(mcpEndpoint).hasAnyRole(VIEWER, OPERATOR, APPROVER)
						.pathMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(VIEWER, OPERATOR, APPROVER, ADMIN)
						.pathMatchers(HttpMethod.POST, "/api/v1/tasks", "/api/v1/runs/*/cancel", "/api/v1/runs/*/resume",
								"/api/v1/runs/*/revisions")
						.hasRole(OPERATOR)
						.pathMatchers(HttpMethod.POST, "/api/v1/runs/*/decisions", "/api/v1/runs/*/risk", "/api/v1/memory/*/status")
						.hasRole(APPROVER)
						.anyExchange().denyAll())
				.oauth2ResourceServer(oauth2 -> oauth2
						.authenticationEntryPoint(SecurityConfiguration::challenge)
						.authenticationManagerResolver(managers(apiTokens, decoders,
								properties.security().local() ? "roles" : properties.security().rolesClaim())))
				.headers(headers -> headers
						// The UI renews tokens in a hidden same-origin iframe (silent-renew.html).
						.frameOptions(frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.SAMEORIGIN))
						.contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy(properties.ui().issuer())))
						.referrerPolicy(referrer -> referrer.policy(
								org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER)))
				.build();
	}

	/**
	 * Bearer tokens starting with {@link ApiTokens#PREFIX} are personal API tokens, looked up in the database; any other
	 * bearer token is a JWT (from the identity provider, or the app's own in local mode). Both end up as a
	 * {@link JwtAuthenticationToken}, so controllers and tools see the same principal either way.
	 */
	static ReactiveAuthenticationManagerResolver<ServerWebExchange> managers(ApiTokens apiTokens,
			ObjectProvider<ReactiveJwtDecoder> decoders, String rolesClaim) {
		ReactiveAuthenticationManager tokens = authentication -> apiTokens
				.authenticate(((BearerTokenAuthenticationToken) authentication).getToken())
				.<Authentication>map(jwt -> new JwtAuthenticationToken(jwt, roles(jwt.getClaims(), "roles"), jwt.getSubject()))
				.switchIfEmpty(Mono.error(() -> new InvalidBearerTokenException("unknown, expired or revoked API token")));
		Mono<ReactiveAuthenticationManager> jwts = Mono.fromSupplier(() -> {
			JwtReactiveAuthenticationManager manager = new JwtReactiveAuthenticationManager(decoders.getObject());
			manager.setJwtAuthenticationConverter(rolesConverter(rolesClaim));
			return (ReactiveAuthenticationManager) manager;
		}).cacheInvalidateIf(m -> false);
		return exchange -> {
			String header = exchange.getRequest().getHeaders().getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION);
			boolean apiToken = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
					&& ApiTokens.looksLikeToken(header.substring(7).strip());
			return apiToken ? Mono.just(tokens) : jwts;
		};
	}

	/**
	 * Scripts only from this origin; the identity provider is reachable for token calls and silent renew, and only this
	 * origin may frame the app (the silent-renew iframe).
	 */
	static String contentSecurityPolicy(String issuer) {
		String idp = issuer == null || issuer.isBlank() ? "" : " " + java.net.URI.create(issuer).resolve("/").toString()
				.replaceAll("/$", "");
		return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
				+ "connect-src 'self'" + idp + "; frame-src 'self'" + idp + "; frame-ancestors 'self'; base-uri 'self'; "
				+ "form-action 'self'" + idp + "; object-src 'none'";
	}

	/**
	 * A 401 that also tells MCP clients where to find the authorization server (RFC 9728 {@code resource_metadata}),
	 * keeping RFC 6750's error details for rejected tokens.
	 */
	static Mono<Void> challenge(ServerWebExchange exchange, AuthenticationException failure) {
		List<String> params = new java.util.ArrayList<>();
		if (failure instanceof OAuth2AuthenticationException oauth && oauth.getError() instanceof BearerTokenError error) {
			params.add("error=\"" + error.getErrorCode() + "\"");
			if (error.getDescription() != null) {
				params.add("error_description=\"" + error.getDescription().replace("\"", "'") + "\"");
			}
		}
		params.add("resource_metadata=\"" + ProtectedResourceMetadata.metadataUrl(exchange.getRequest()) + "\"");
		exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
		exchange.getResponse().getHeaders().set(org.springframework.http.HttpHeaders.WWW_AUTHENTICATE,
				"Bearer " + String.join(", ", params));
		return exchange.getResponse().setComplete();
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

	private static CorsConfigurationSource cors(List<String> allowedOrigins, String mcpEndpoint) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID"));
		config.setExposedHeaders(List.of("Location"));
		config.setMaxAge(3600L);
		// Browser-based MCP clients (e.g. the MCP Inspector) from the same allowed origins.
		CorsConfiguration mcp = new CorsConfiguration(config);
		mcp.setAllowedMethods(List.of("GET", "POST", "DELETE"));
		mcp.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Mcp-Protocol-Version", "Mcp-Session-Id"));
		mcp.setExposedHeaders(List.of("Mcp-Session-Id", "WWW-Authenticate"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		source.registerCorsConfiguration(mcpEndpoint, mcp);
		return source;
	}
}
