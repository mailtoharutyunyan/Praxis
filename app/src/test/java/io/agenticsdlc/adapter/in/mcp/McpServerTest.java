package io.agenticsdlc.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import io.agenticsdlc.TestcontainersConfigurationAccess;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The MCP endpoint as an AI client sees it: discovery, authentication, the tool list and role checks. */
@Import(TestcontainersConfigurationAccess.class)
@SpringBootTest(properties = { "agentic.stub-stages.enabled=true", "agentic.sandbox.enabled=false",
		"agentic.worker.enabled=false", "agentic.mcp.run-link-base=https://agentic.example.com/#/runs/" })
class McpServerTest {

	@Autowired
	ApplicationContext context;

	private WebTestClient client;
	private final JsonMapper json = JsonMapper.builder().build();
	private int ids;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient()
				.responseTimeout(Duration.ofSeconds(20)).build();
	}

	private static JwtMutator user(String subject, String... roles) {
		return mockJwt().jwt(jwt -> jwt.subject(subject)).authorities(Arrays.stream(roles)
				.map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())).toList());
	}

	/** One JSON-RPC request to /mcp; returns the "result" member. */
	private JsonNode rpc(JwtMutator user, String method, Map<String, Object> params) {
		String body = client.mutateWith(user).post().uri("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.bodyValue(Map.of("jsonrpc", "2.0", "id", ++ids, "method", method, "params", params))
				.exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();
		JsonNode response = json.readTree(body);
		assertThat(response.has("error")).as("JSON-RPC error: %s", body).isFalse();
		return response.path("result");
	}

	private JsonNode call(JwtMutator user, String tool, Map<String, Object> arguments) {
		return rpc(user, "tools/call", Map.of("name", tool, "arguments", arguments));
	}

	private static String text(JsonNode result) {
		return result.path("content").get(0).path("text").asString();
	}

	@Test
	void unauthenticatedClientsAreToldWhereToSignIn() {
		client.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
				.expectStatus().isUnauthorized()
				.expectHeader().value("WWW-Authenticate", header -> assertThat(header).startsWith("Bearer ")
						.contains("resource_metadata=\"").contains("/.well-known/oauth-protected-resource/mcp\""));

		client.get().uri("/.well-known/oauth-protected-resource/mcp").exchange()
				.expectStatus().isOk()
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
				})
				.value(metadata -> {
					assertThat(String.valueOf(metadata.get("resource"))).endsWith("/mcp");
					assertThat(metadata.get("bearer_methods_supported")).isEqualTo(List.of("header"));
				});
	}

	@Test
	void toolsAreListedWithoutAnyWayToApproveGates() {
		JsonNode init = rpc(user("v", "viewer"), "initialize", Map.of("protocolVersion", "2025-06-18",
				"capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
		assertThat(init.path("serverInfo").path("name").asString()).isEqualTo("agentic-sdlc");
		assertThat(init.path("instructions").asString()).contains("you cannot approve gates");

		JsonNode tools = rpc(user("v", "viewer"), "tools/list", Map.of()).path("tools");
		List<String> names = tools.valueStream().map(t -> t.path("name").asString()).toList();
		assertThat(names).containsExactlyInAnyOrder("submit_task", "list_runs", "get_run", "get_run_artifact",
				"get_run_events", "cancel_run", "resume_run");
		JsonNode submit = tools.valueStream().filter(t -> t.path("name").asString().equals("submit_task")).findFirst()
				.orElseThrow();
		assertThat(submit.path("inputSchema").path("required").valueStream().map(JsonNode::asString).toList())
				.contains("title", "description", "cloneUrl", "repositoryKind").doesNotContain("baseBranch");
		assertThat(tools.valueStream().filter(t -> t.path("name").asString().equals("get_run")).findFirst().orElseThrow()
				.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
	}

	@Test
	void operatorsSubmitUntrustedTasksAndViewersCanOnlyRead() {
		Map<String, Object> task = Map.of("title", "Add order search", "description", "Search orders by email",
				"cloneUrl", "https://github.com/acme/shop.git", "repositoryKind", "github");

		JsonNode denied = call(user("v", "viewer"), "submit_task", task);
		assertThat(denied.path("isError").asBoolean()).isTrue();
		assertThat(text(denied)).contains("operator");

		JsonNode submitted = call(user("alice", "operator"), "submit_task", task);
		assertThat(submitted.path("isError").asBoolean(false)).as(text(submitted)).isFalse();
		JsonNode run = json.readTree(text(submitted));
		String runId = run.path("id").asString();
		assertThat(run.path("trust").asString()).isEqualTo("UNTRUSTED");
		assertThat(run.path("requestedBy").asString()).isEqualTo("alice");
		assertThat(run.path("link").asString()).isEqualTo("https://agentic.example.com/#/runs/" + runId);

		JsonNode fetched = json.readTree(text(call(user("v", "viewer"), "get_run", Map.of("runId", runId))));
		assertThat(fetched.path("id").asString()).isEqualTo(runId);
		assertThat(fetched.path("next").asString()).isNotBlank();

		JsonNode events = json.readTree(text(call(user("v", "viewer"), "get_run_events", Map.of("runId", runId))));
		assertThat(events.get(0).path("type").asString()).isEqualTo("RUN_CREATED");

		JsonNode noSpec = call(user("v", "viewer"), "get_run_artifact", Map.of("runId", runId, "kind", "spec"));
		assertThat(noSpec.path("isError").asBoolean()).isTrue();
		assertThat(text(noSpec)).contains("has no spec yet");

		assertThat(call(user("v", "viewer"), "cancel_run", Map.of("runId", runId)).path("isError").asBoolean()).isTrue();
		JsonNode cancelled = json.readTree(text(call(user("alice", "operator"), "cancel_run",
				Map.of("runId", runId, "reason", "test"))));
		assertThat(cancelled.path("state").asString()).isEqualTo("CANCELLED");

		JsonNode badId = call(user("v", "viewer"), "get_run", Map.of("runId", "not-a-uuid"));
		assertThat(badId.path("isError").asBoolean()).isTrue();
		assertThat(text(badId)).contains("must be a UUID");
	}
}
