package io.agenticsdlc.config.connectors;

import io.agenticsdlc.adapter.out.llm.ModelProbe;
import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.connectors.ConnectorSettings.Connector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** "Test connection" for each connector: one cheap, read-only call with the given credentials. */
public final class ConnectorTester {

	public record Result(boolean ok, String message) {
	}

	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private final WebClient client;

	public ConnectorTester(WebClient.Builder builder) {
		this.client = builder.build();
	}

	public Mono<Result> test(Connector connector) {
		Mono<Result> check = switch (connector.id()) {
			case ConnectorCatalog.GIT -> git(connector);
			case ConnectorCatalog.MODELS -> models(connector);
			case ConnectorCatalog.JIRA -> jira(connector);
			case ConnectorCatalog.SLACK -> slack(connector);
			default -> Mono.just(new Result(true, "Nothing to test; it is checked when it is used."));
		};
		return check.onErrorResume(e -> Mono.just(new Result(false, describe(e))));
	}

	private Mono<Result> git(Connector c) {
		List<Mono<String>> checks = new ArrayList<>();
		for (Map<String, Object> host : c.items("hosts")) {
			String name = String.valueOf(host.get("host"));
			String kind = String.valueOf(host.get("kind"));
			String token = c.secret("hosts." + name + ".token");
			String api = String.valueOf(host.getOrDefault("apiUrl", "")).strip();
			checks.add(switch (kind) {
				case "GITHUB" -> json(api.isEmpty() ? (name.equals("github.com") ? "https://api.github.com"
						: "https://" + name + "/api/v3") + "/user" : api + "/user", h -> h.setBearerAuth(token))
						.map(user -> name + ": signed in as " + user.path("login").asString("?"));
				case "GITLAB" -> json((api.isEmpty() ? "https://" + name + "/api/v4" : api) + "/user",
						h -> h.set("PRIVATE-TOKEN", token))
						.map(user -> name + ": signed in as " + user.path("username").asString("?"));
				case "BITBUCKET" -> json((api.isEmpty() ? "https://api.bitbucket.org/2.0" : api) + "/repositories?role=member&pagelen=1",
						h -> h.setBearerAuth(token)).map(page -> name + ": token accepted");
				case "AZURE_DEVOPS" -> {
					String org = String.valueOf(host.getOrDefault("organization", "")).strip();
					String basic = Base64.getEncoder().encodeToString((":" + token).getBytes(StandardCharsets.UTF_8));
					yield org.isEmpty() ? Mono.just(name + ": saved (add the organization to test the token)")
							: json("https://dev.azure.com/" + org + "/_apis/projects?api-version=7.1",
									h -> h.set(HttpHeaders.AUTHORIZATION, "Basic " + basic))
									.map(projects -> name + ": " + projects.path("count").asInt(0) + " projects visible");
				}
				default -> Mono.just(name + ": unknown provider " + kind);
			});
		}
		return Flux.concat(checks).collectList().map(lines -> new Result(true, String.join("\n", lines)));
	}

	private Mono<Result> models(Connector c) {
		if (ConnectorCatalog.ENGINE_CLAUDE_CODE.equals(c.text("engine")) && c.secret("apiKey").isBlank()
				&& !List.of("ollama", "bedrock").contains(c.text("provider"))) {
			// The token only works in the CLI, which runs in a run's sandbox: nothing to call from here.
			return Mono.just(new Result(true, "Saved. Claude Code runs in each run's sandbox, so the token is first used "
					+ "by the next run. Tasks from Jira, Slack and other outside sources need an API key here, or they "
					+ "wait for a human."));
		}
		String provider = c.text("provider");
		if (provider.equals("ollama")) {
			String base = c.text("baseUrl").isEmpty() ? "http://localhost:11434" : c.text("baseUrl");
			return json(base + "/api/tags", h -> {
			}).map(tags -> new Result(true, "Ollama answers; " + tags.path("models").size() + " models installed"));
		}
		return ModelProbe.probe(new AgenticProperties.Provider(provider, c.secret("apiKey"), c.text("baseUrl"),
				c.text("region"), c.text("deployment")), c.text("model"))
				.map(reply -> new Result(true, c.text("model") + " answered: " + (reply.length() <= 40 ? reply
						: reply.substring(0, 40) + "…")));
	}

	private Mono<Result> jira(Connector c) {
		boolean cloud = !"data-center".equals(c.text("deployment"));
		String base = c.text("baseUrl").replaceAll("/+$", "");
		String email = c.text("email");
		String token = c.secret("apiToken");
		String auth = email.isEmpty() ? "Bearer " + token
				: "Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
		return json(base + (cloud ? "/rest/api/3" : "/rest/api/2") + "/myself", h -> h.set(HttpHeaders.AUTHORIZATION, auth))
				.map(me -> new Result(true, "Signed in to Jira as " + me.path("displayName").asString("?")));
	}

	private Mono<Result> slack(Connector c) {
		return client.post().uri(URI.create("https://slack.com/api/auth.test"))
				.headers(h -> h.setBearerAuth(c.secret("botToken")))
				.retrieve().bodyToMono(JsonNode.class).timeout(TIMEOUT)
				.map(r -> r.path("ok").asBoolean(false)
						? new Result(true, "Connected to " + r.path("team").asString("?") + " as " + r.path("user").asString("?"))
						: new Result(false, "Slack says: " + r.path("error").asString("unknown error")));
	}

	private Mono<JsonNode> json(String url, java.util.function.Consumer<HttpHeaders> headers) {
		return client.get().uri(URI.create(url)).headers(headers).retrieve().bodyToMono(JsonNode.class).timeout(TIMEOUT);
	}

	private static String describe(Throwable e) {
		if (e instanceof WebClientResponseException w) {
			return switch (w.getStatusCode().value()) {
				case 401 -> "The credentials were rejected (401).";
				case 403 -> "The credentials work but lack permissions (403).";
				case 404 -> "Not found (404): check the URL.";
				default -> "The server answered " + w.getStatusCode().value() + ".";
			};
		}
		return "Could not connect: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
	}
}
