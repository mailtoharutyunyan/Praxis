package io.agenticsdlc.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import io.agenticsdlc.TestcontainersConfigurationAccess;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

/** The v1 API end to end: security rules, validation, problem details, and a run driven through all gates. */
@Import(TestcontainersConfigurationAccess.class)
@SpringBootTest(properties = { "agentic.stub-stages.enabled=true", "agentic.sandbox.enabled=false", "agentic.worker.poll-interval=50ms",
		"agentic.events.fallback-poll=200ms" })
class RunApiTest {

	@Autowired
	ApplicationContext context;

	WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient()
				.responseTimeout(Duration.ofSeconds(20)).build();
	}

	private static JwtMutator user(String subject, String... roles) {
		return mockJwt().jwt(jwt -> jwt.subject(subject))
				.authorities(java.util.Arrays.stream(roles)
						.map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())).toList());
	}

	private static Map<String, Object> task(String title) {
		return Map.of("title", title, "description", "Add a search endpoint with tests",
				"repository", Map.of("kind", "GITHUB", "cloneUrl", "https://github.com/acme/shop.git"));
	}

	private Map<String, Object> submit(String title) {
		return client.mutateWith(user("alice", "operator")).post().uri("/api/v1/tasks").bodyValue(task(title))
				.exchange().expectStatus().isCreated()
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
				}).returnResult().getResponseBody();
	}

	private Map<String, Object> run(Object id) {
		return client.mutateWith(user("viewer", "viewer")).get().uri("/api/v1/runs/{id}", id).exchange()
				.expectStatus().isOk()
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
				}).returnResult().getResponseBody();
	}

	private Map<String, Object> awaitRun(Object id, Predicate<Map<String, Object>> condition) {
		long deadline = System.currentTimeMillis() + 20_000;
		Map<String, Object> current = run(id);
		while (!condition.test(current)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("run did not reach expected state, last: " + current);
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
			current = run(id);
		}
		return current;
	}

	private static Predicate<Map<String, Object>> waitingAt(String gate) {
		return r -> "AWAITING_APPROVAL".equals(r.get("state")) && gate.equals(r.get("pendingGate"));
	}

	private void decide(Object id, String gate, String decision) {
		client.mutateWith(user("bob", "approver")).post().uri("/api/v1/runs/{id}/decisions", id)
				.bodyValue(Map.of("gate", gate, "decision", decision, "comment", "ok")).exchange()
				.expectStatus().isOk();
	}

	@Test
	void mediumRiskRunPassesSpecAndPublishGatesToPrOpen() {
		Object id = submit("[medium] Add search").get("id");

		Map<String, Object> atSpec = awaitRun(id, waitingAt("SPEC"));
		assertThat(atSpec.get("risk")).isEqualTo("MEDIUM");
		assertThat(atSpec.get("gates")).isEqualTo(List.of("SPEC", "PUBLISH"));
		decide(id, "SPEC", "APPROVE");

		awaitRun(id, waitingAt("PUBLISH"));
		client.mutateWith(user("alice", "operator")).post().uri("/api/v1/runs/{id}/decisions", id)
				.bodyValue(Map.of("gate", "PUBLISH", "decision", "APPROVE")).exchange()
				.expectStatus().isForbidden();
		decide(id, "PUBLISH", "APPROVE");

		awaitRun(id, r -> "PR_OPEN".equals(r.get("state")));

		List<Map<String, Object>> events = client.mutateWith(user("viewer", "viewer")).get()
				.uri("/api/v1/runs/{id}/events?limit=500", id).accept(MediaType.APPLICATION_JSON).exchange()
				.expectStatus().isOk()
				.expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {
				}).returnResult().getResponseBody();
		assertThat(events).extracting(e -> e.get("type")).contains("RUN_CREATED", "TRIAGED", "GATE_OPENED",
				"GATE_DECIDED", "AGENT_MESSAGE");
		assertThat(events).extracting(e -> ((Number) e.get("seq")).longValue()).isSorted().doesNotHaveDuplicates();
	}

	@Test
	void decisionOnWrongGateIsConflict() {
		Object id = submit("[medium] wrong gate").get("id");
		awaitRun(id, waitingAt("SPEC"));
		client.mutateWith(user("bob", "approver")).post().uri("/api/v1/runs/{id}/decisions", id)
				.bodyValue(Map.of("gate", "PUBLISH", "decision", "APPROVE")).exchange()
				.expectStatus().isEqualTo(409)
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.type").isEqualTo("https://agentic-sdlc.dev/problems/invalid-state");
	}

	@Test
	void liveStreamDeliversEventsAndEndsWhenRunIsCancelled() {
		Object id = submit("[medium] stream me").get("id");
		awaitRun(id, waitingAt("SPEC"));

		var stream = client.mutateWith(user("viewer", "viewer")).get().uri("/api/v1/runs/{id}/events", id)
				.accept(MediaType.TEXT_EVENT_STREAM).exchange().expectStatus().isOk()
				.returnResult(new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {
				}).getResponseBody().filter(e -> e.data() != null);

		StepVerifier.create(stream)
				.expectNextMatches(e -> "RUN_CREATED".equals(e.event()) && "1".equals(e.id()))
				.thenConsumeWhile(e -> !"GATE_OPENED".equals(e.event()))
				.expectNextMatches(e -> "GATE_OPENED".equals(e.event()))
				.expectNextMatches(e -> "STATE_CHANGED".equals(e.event()))
				.then(() -> client.mutateWith(user("alice", "operator")).post().uri("/api/v1/runs/{id}/cancel", id)
						.bodyValue(Map.of("reason", "test")).exchange().expectStatus().isOk())
				.expectNextMatches(e -> "ERROR".equals(e.event()))
				.expectNextMatches(e -> "CANCELLED".equals(e.data().get("payload") instanceof Map<?, ?> p ? p.get("to") : null))
				.expectComplete()
				.verify(Duration.ofSeconds(20));
	}

	@Test
	void streamResumesAfterLastEventId() {
		Object id = submit("[medium] resume stream").get("id");
		awaitRun(id, waitingAt("SPEC"));
		client.mutateWith(user("alice", "operator")).post().uri("/api/v1/runs/{id}/cancel", id).exchange()
				.expectStatus().isOk();

		var stream = client.mutateWith(user("viewer", "viewer")).get().uri("/api/v1/runs/{id}/events", id)
				.header("Last-Event-ID", "1").accept(MediaType.TEXT_EVENT_STREAM).exchange().expectStatus().isOk()
				.returnResult(new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {
				}).getResponseBody().filter(e -> e.data() != null);
		StepVerifier.create(stream).expectNextMatches(e -> "2".equals(e.id())).thenConsumeWhile(e -> true)
				.expectComplete().verify(Duration.ofSeconds(20));
	}

	@Test
	void idempotentSubmission() {
		String key = UUID.randomUUID().toString();
		String first = client.mutateWith(user("carol", "operator")).post().uri("/api/v1/tasks")
				.header("Idempotency-Key", key).bodyValue(task("idempotent")).exchange().expectStatus().isCreated()
				.expectBody().returnResult().getResponseHeaders().getLocation().toString();
		String second = client.mutateWith(user("carol", "operator")).post().uri("/api/v1/tasks")
				.header("Idempotency-Key", key).bodyValue(task("idempotent")).exchange().expectStatus().isOk()
				.expectBody().returnResult().getResponseHeaders().getLocation().toString();
		assertThat(second).isEqualTo(first);
	}

	@Test
	void listsRunsWithStateFilter() {
		submit("[medium] list me");
		client.mutateWith(user("viewer", "viewer")).get().uri("/api/v1/runs?state=RECEIVED&state=AWAITING_APPROVAL&limit=5")
				.exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.items").isArray();
	}

	@Test
	void securityRules() {
		client.get().uri("/api/v1/runs").exchange().expectStatus().isUnauthorized();
		client.mutateWith(user("v", "viewer")).post().uri("/api/v1/tasks").bodyValue(task("x")).exchange()
				.expectStatus().isForbidden();
		client.mutateWith(user("o", "operator")).post().uri("/api/v1/runs/{id}/risk", UUID.randomUUID())
				.bodyValue(Map.of("risk", "HIGH", "reason", "x")).exchange().expectStatus().isForbidden();
		client.mutateWith(user("o", "operator")).delete().uri("/api/v1/runs/{id}", UUID.randomUUID()).exchange()
				.expectStatus().isForbidden();
		client.get().uri("/actuator/health").exchange().expectStatus().isOk()
				.expectHeader().valueEquals("X-Frame-Options", "SAMEORIGIN")
				.expectHeader().value("Content-Security-Policy", csp -> assertThat(csp).contains("frame-ancestors 'self'"));
		// Public like the rest of the UI shell; 404 here only because tests run without the built UI.
		client.get().uri("/silent-renew.html").exchange().expectStatus().isNotFound();
	}

	@Test
	void validationAndNotFoundAreProblemDetails() {
		client.mutateWith(user("o", "operator")).post().uri("/api/v1/tasks")
				.bodyValue(Map.of("title", " ", "description", "d")).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

		client.mutateWith(user("o", "operator")).post().uri("/api/v1/tasks")
				.bodyValue(Map.of("title", "t", "description", "d",
						"repository", Map.of("kind", "GITHUB", "cloneUrl", "http://insecure.example/repo.git")))
				.exchange().expectStatus().isBadRequest()
				.expectBody().jsonPath("$.detail").isEqualTo("cloneUrl must use https");

		// Parameter constraints are request errors (400 problem details), not server errors.
		client.mutateWith(user("v", "viewer")).get().uri("/api/v1/runs?limit=0").exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
		client.mutateWith(user("v", "viewer")).get().uri("/api/v1/runs?limit=100000").exchange()
				.expectStatus().isBadRequest();

		client.mutateWith(user("v", "viewer")).get().uri("/api/v1/runs/{id}", UUID.randomUUID()).exchange()
				.expectStatus().isNotFound()
				.expectBody().jsonPath("$.title").isEqualTo("Run not found");
	}

	@Test
	void raiseRiskAndResumeFlows() {
		Object id = submit("[medium] raise").get("id");
		awaitRun(id, waitingAt("SPEC"));
		client.mutateWith(user("bob", "approver")).post().uri("/api/v1/runs/{id}/risk", id)
				.bodyValue(Map.of("risk", "HIGH", "reason", "touches auth")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.gates.length()").isEqualTo(3);
		client.mutateWith(user("alice", "operator")).post().uri("/api/v1/runs/{id}/resume", id).exchange()
				.expectStatus().isEqualTo(409);
	}
}
