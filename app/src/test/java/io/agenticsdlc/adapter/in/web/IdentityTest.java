package io.agenticsdlc.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.config.identity.LocalAuth;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Built-in accounts and personal API tokens end to end: tokens work for the API and for MCP (AI CLIs) but cannot
 * mint tokens or change settings; sign-out, password and role changes end sessions; lockout; user administration.
 */
@SpringBootTest(classes = { io.agenticsdlc.AgenticSdlcApplication.class, IdentityTest.Postgres.class },
		properties = { "agentic.security.mode=local", "agentic.stub-stages.enabled=true", "agentic.sandbox.enabled=false",
				"agentic.worker.poll-interval=50ms", "agentic.security.local-token-ttl=2h" })
class IdentityTest {

	private static final String PASSWORD = "correct horse battery";

	@TestConfiguration(proxyBeanMethods = false)
	static class Postgres {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}
	}

	@Autowired
	ApplicationContext context;

	@Autowired
	LocalAuth localAuth;

	private final JsonMapper json = JsonMapper.builder().build();
	private String admin;

	private WebTestClient client() {
		return WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(20))
				.build();
	}

	private WebTestClient as(String token) {
		return client().mutate().defaultHeader("Authorization", "Bearer " + token).build();
	}

	private String login(String username, String password) {
		Map<?, ?> body = client().post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", username, "password", password)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (String) body.get("token");
	}

	@BeforeEach
	void admin() {
		if (!Boolean.TRUE.equals(localAuth.adminExists().block())) {
			client().post().uri("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("setupCode", localAuth.setupCode().block(), "username", "admin", "password", PASSWORD))
					.exchange().expectStatus().isOk();
		}
		admin = login("admin", PASSWORD);
	}

	private Map<?, ?> createToken(String session, String name, List<String> roles) {
		return as(session).post().uri("/api/v1/tokens").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("name", name, "roles", roles, "expiresInDays", 30)).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
	}

	@Test
	void apiTokensWorkForTheApiAndMcpButCannotMintTokensOrChangeSettings() {
		Map<?, ?> created = createToken(admin, "claude code", List.of("viewer", "operator"));
		String token = (String) created.get("token");
		assertThat(token).startsWith("asdlc_");

		as(token).get().uri("/api/v1/runs").exchange().expectStatus().isOk();
		as(token).post().uri("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "t-1")
				.bodyValue(Map.of("title", "Add a health endpoint", "description", "GET /health [low]", "repository",
						Map.of("kind", "GITHUB", "cloneUrl", "https://github.com/acme/shop.git")))
				.exchange().expectStatus().isCreated();
		// A token never carries admin, and cannot mint more tokens.
		as(token).get().uri("/api/v1/connectors").exchange().expectStatus().isForbidden();
		as(token).post().uri("/api/v1/tokens").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("name", "more", "roles", List.of("viewer"), "expiresInDays", 1)).exchange()
				.expectStatus().isForbidden();
		as(admin).post().uri("/api/v1/tokens").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("name", "root", "roles", List.of("admin"), "expiresInDays", 1)).exchange()
				.expectStatus().isBadRequest();

		// An AI CLI connects to MCP with the token.
		String init = as(token).post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params",
						Map.of("name", "list_runs", "arguments", Map.of())))
				.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		JsonNode result = json.readTree(init).path("result");
		assertThat(result.path("isError").asBoolean(false)).as(init).isFalse();
		assertThat(result.path("content").get(0).path("text").asString()).contains("Add a health endpoint");

		// Listed without the secret; revoked tokens stop working at once.
		String listed = as(admin).get().uri("/api/v1/tokens").exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(listed).contains("claude code").doesNotContain(token);
		String id = (String) ((Map<?, ?>) created.get("details")).get("id");
		as(admin).delete().uri("/api/v1/tokens/" + id).exchange().expectStatus().isNoContent();
		as(token).get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();
		as("asdlc_not-a-real-token").get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void signingOutAndChangingThePasswordEndSessions() {
		String other = login("admin", PASSWORD);
		as(admin).post().uri("/api/v1/auth/logout").exchange().expectStatus().isNoContent();
		as(admin).get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();
		as(other).get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();

		String session = login("admin", PASSWORD);
		as(session).post().uri("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("currentPassword", "wrong password!", "newPassword", "another long password"))
				.exchange().expectStatus().isBadRequest();
		Map<?, ?> changed = as(session).post().uri("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("currentPassword", PASSWORD, "newPassword", "another long password")).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		as(session).get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();
		as((String) changed.get("token")).get().uri("/api/v1/runs").exchange().expectStatus().isOk();

		// Restore the shared password for the other tests.
		as((String) changed.get("token")).post().uri("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("currentPassword", "another long password", "newPassword", PASSWORD)).exchange()
				.expectStatus().isOk();
	}

	@Test
	void adminsManageUsersAndTheLastAdminStays() {
		as(admin).post().uri("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "olga", "password", "olga's long password", "roles", List.of("operator")))
				.exchange().expectStatus().isCreated();
		String olga = login("olga", "olga's long password");
		as(olga).get().uri("/api/v1/users").exchange().expectStatus().isForbidden();
		as(olga).post().uri("/api/v1/runs/00000000-0000-0000-0000-000000000000/decisions")
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("gate", "SPEC", "decision", "APPROVE"))
				.exchange().expectStatus().isForbidden();

		// A role change ends her sessions, and her API tokens stop when she is deleted.
		String token = (String) createToken(olga, "script", List.of("operator")).get("token");
		as(admin).put().uri("/api/v1/users/olga/roles").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("roles", List.of("viewer", "approver"))).exchange().expectStatus().isNoContent();
		as(olga).get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();
		as(token).get().uri("/api/v1/runs").exchange().expectStatus().isOk();
		as(admin).delete().uri("/api/v1/users/olga").exchange().expectStatus().isNoContent();
		as(token).get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();

		as(admin).delete().uri("/api/v1/users/admin").exchange().expectStatus().isBadRequest();
		as(admin).put().uri("/api/v1/users/admin/roles").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("roles", List.of("viewer"))).exchange().expectStatus().isEqualTo(409);
	}

	@Test
	void repeatedWrongPasswordsLockTheAccountForAMinute() {
		as(admin).post().uri("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "lena", "password", "lena's long password", "roles", List.of("viewer")))
				.exchange().expectStatus().isCreated();
		for (int i = 0; i < 5; i++) {
			client().post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("username", "lena", "password", "not her password")).exchange().expectStatus()
					.isUnauthorized();
		}
		client().post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "lena", "password", "lena's long password")).exchange().expectStatus()
				.isEqualTo(429);
		client().post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "nobody", "password", "whatever123")).exchange().expectStatus()
				.isUnauthorized();
	}
}
