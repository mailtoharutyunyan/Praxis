package io.agenticsdlc.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.config.connectors.ConnectorStore;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.support.FakeScmServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ADR-0007 end to end in local sign-in mode: first-run status, the first admin, sign-in, connectors (secrets never
 * returned, required ones not skippable), setup completion, and a signed Slack command starting an untrusted run.
 */
@SpringBootTest(classes = { io.agenticsdlc.AgenticSdlcApplication.class, OnboardingTest.Postgres.class },
		properties = { "agentic.security.mode=local", "agentic.stub-stages.enabled=true", "agentic.sandbox.enabled=false",
				"agentic.worker.poll-interval=50ms" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingTest {

	private static final FakeScmServer SLACK;
	private static final String SIGNING_SECRET = "slack-signing-secret";
	private static String token;

	static {
		try {
			SLACK = new FakeScmServer().on("POST", "/api/chat.postMessage",
					r -> new FakeScmServer.Response(200, "{\"ok\":true,\"ts\":\"1712345678.000100\"}"));
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Postgres {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.slack.api-url", () -> SLACK.url() + "/api");
	}

	@AfterAll
	static void stop() {
		SLACK.close();
	}

	@Autowired
	ApplicationContext context;

	@Autowired
	ConnectorStore store;

	@Autowired
	ConnectorSettings settings;

	@Autowired
	RunQueries queries;

	private WebTestClient client() {
		return WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(20))
				.build();
	}

	private WebTestClient admin() {
		return client().mutate().defaultHeader("Authorization", "Bearer " + token).build();
	}

	@Test
	@Order(1)
	void firstRunNeedsAnAdminAndTheRequiredConnectors() {
		Map<?, ?> status = client().get().uri("/api/v1/setup").exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		assertThat(status.get("authMode")).isEqualTo("local");
		assertThat(status.get("complete")).isEqualTo(false);
		assertThat(states(status)).containsEntry("admin", "PENDING").containsEntry("git", "PENDING")
				.containsEntry("jira", "PENDING");
		client().get().uri("/api/v1/connectors").exchange().expectStatus().isUnauthorized();
		client().get().uri("/ui-config.json").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.authMode").isEqualTo("local");
	}

	@Test
	@Order(2)
	void theFirstAdminIsCreatedOnceAndSignsIn() {
		client().post().uri("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "admin", "password", "short")).exchange().expectStatus().isBadRequest();
		Map<?, ?> created = client().post().uri("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "Admin", "password", "correct horse battery")).exchange().expectStatus()
				.isOk().expectBody(Map.class).returnResult().getResponseBody();
		assertThat(created.get("roles")).isEqualTo(List.of("viewer", "operator", "approver", "admin"));
		client().post().uri("/api/v1/setup/admin").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "other", "password", "correct horse battery")).exchange().expectStatus()
				.isEqualTo(409);

		client().post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "admin", "password", "wrong password!")).exchange().expectStatus()
				.isUnauthorized();
		Map<?, ?> signedIn = client().post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("username", "admin", "password", "correct horse battery")).exchange().expectStatus()
				.isOk().expectBody(Map.class).returnResult().getResponseBody();
		token = (String) signedIn.get("token");
		admin().get().uri("/api/v1/runs").exchange().expectStatus().isOk();
		client().get().uri("/api/v1/runs").header("Authorization", "Bearer " + token + "x").exchange().expectStatus()
				.isUnauthorized();
	}

	@Test
	@Order(3)
	void connectorsAreSavedEncryptedAndSecretsNeverReturned() {
		admin().put().uri("/api/v1/connectors/git").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("config", Map.of("hosts", List.of(Map.of("kind", "GITHUB", "host", "github.com"))),
						"secrets", Map.of()))
				.exchange().expectStatus().isBadRequest();
		String body = admin().put().uri("/api/v1/connectors/git").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("config", Map.of("hosts", List.of(Map.of("kind", "GITHUB", "host", "github.com"))),
						"secrets", Map.of("hosts.github.com.token", "ghp_example_token_value")))
				.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		assertThat(body).doesNotContain("ghp_example_token_value").contains("hosts.github.com.token");
		assertThat(settings.scmToken("github.com")).contains("ghp_example_token_value");
		assertThat(store.connectors().filter(c -> c.id().equals("git")).blockFirst().encryptedSecrets())
				.doesNotContain("ghp_example");

		// Saving again without the secret keeps it.
		admin().put().uri("/api/v1/connectors/git").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("config", Map.of("hosts", List.of(Map.of("kind", "GITHUB", "host", "github.com"))),
						"secrets", Map.of()))
				.exchange().expectStatus().isOk();
		assertThat(settings.scmToken("github.com")).contains("ghp_example_token_value");

		admin().post().uri("/api/v1/connectors/models/skip").exchange().expectStatus().isBadRequest();
		admin().put().uri("/api/v1/connectors/models").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("config", Map.of("provider", "ollama", "model", "qwen3", "baseUrl",
						"http://ollama:11434"), "secrets", Map.of()))
				.exchange().expectStatus().isOk();
		admin().put().uri("/api/v1/connectors/app").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("config", Map.of("publicUrl", "http://localhost:8080"), "secrets", Map.of()))
				.exchange().expectStatus().isOk();
		for (String optional : List.of("jira", "webhooks")) {
			admin().post().uri("/api/v1/connectors/" + optional + "/skip").exchange().expectStatus().isOk();
		}
		Map<?, ?> status = client().get().uri("/api/v1/setup").exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		assertThat(states(status)).containsEntry("slack", "PENDING").containsEntry("jira", "SKIPPED");
		assertThat(status.get("complete")).isEqualTo(false);

		admin().put().uri("/api/v1/connectors/slack").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("config", Map.of("defaultKind", "GITHUB", "defaultRepository",
						"https://github.com/acme/shop.git"), "secrets", Map.of("botToken", "xoxb-test", "signingSecret",
								SIGNING_SECRET)))
				.exchange().expectStatus().isOk();
		status = client().get().uri("/api/v1/setup").exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		assertThat(status.get("complete")).isEqualTo(true);
		// A host given a token in the UI becomes an allowed repository host.
		assertThat(settings.allowedHosts()).contains("github.com");
	}

	@Test
	@Order(4)
	void aSignedSlackCommandStartsAnUntrustedRunInAThread() throws Exception {
		String form = "token=x&team_id=T1&channel_id=C0123ABC&user_id=U42&command=%2Fagentic&trigger_id=trig-1"
				+ "&text=Add+a+health+endpoint+%3C%21channel%3E";
		long now = Instant.now().getEpochSecond();
		client().post().uri("/api/v1/webhooks/slack/commands").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.header("X-Slack-Request-Timestamp", String.valueOf(now)).header("X-Slack-Signature", "v0=00")
				.bodyValue(form).exchange().expectStatus().isUnauthorized();
		client().post().uri("/api/v1/webhooks/slack/commands").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.header("X-Slack-Request-Timestamp", String.valueOf(now - 600))
				.header("X-Slack-Signature", sign(now - 600, form)).bodyValue(form).exchange().expectStatus()
				.isUnauthorized();

		Map<?, ?> reply = client().post().uri("/api/v1/webhooks/slack/commands")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).header("X-Slack-Request-Timestamp", String.valueOf(now))
				.header("X-Slack-Signature", sign(now, form)).bodyValue(form).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		assertThat(reply.get("response_type")).isEqualTo("ephemeral");
		assertThat((String) reply.get("text")).startsWith("Started http://localhost:8080/#/runs/");
		UUID runId = UUID.fromString(((String) reply.get("text")).replaceAll(".*/#/runs/([0-9a-f-]{36}).*", "$1"));
		var view = queries.get(runId).block();
		assertThat(view.task().origin()).isEqualTo(TaskOrigin.SLACK);
		assertThat(view.task().trust()).isEqualTo(Trust.UNTRUSTED);
		assertThat(view.task().externalRef()).isEqualTo("C0123ABC:1712345678.000100");
		assertThat(view.task().repository().cloneUrl().toString()).isEqualTo("https://github.com/acme/shop.git");
		assertThat(view.task().requestedBy()).isEqualTo("slack:U42");
		// The echoed request cannot ping the channel.
		assertThat(SLACK.requests).anySatisfy(r -> assertThat(r.body()).contains("&lt;!channel&gt;")
				.doesNotContain("<!channel>"));
	}

	private static String sign(long timestamp, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "v0=" + HexFormat.of().formatHex(mac.doFinal(("v0:" + timestamp + ":" + body)
				.getBytes(StandardCharsets.UTF_8)));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> states(Map<?, ?> status) {
		Map<String, String> states = new java.util.LinkedHashMap<>();
		for (Map<String, Object> step : (List<Map<String, Object>>) status.get("steps")) {
			states.put((String) step.get("id"), (String) step.get("state"));
		}
		return states;
	}
}
