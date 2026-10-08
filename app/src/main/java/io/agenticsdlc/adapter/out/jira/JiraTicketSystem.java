package io.agenticsdlc.adapter.out.jira;

import io.agenticsdlc.core.intake.Ticket;
import io.agenticsdlc.core.intake.TicketSystem;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

/**
 * Jira Cloud REST API v3: reads issues (description in ADF, converted to text) and posts plain-text comments as ADF.
 * Authentication is Basic with an Atlassian account email and API token (Cloud), or a Bearer personal access token.
 */
public class JiraTicketSystem implements TicketSystem {

	private final WebClient client;
	private final String baseUrl;
	private final String authorization;
	private final Duration timeout;

	/**
	 * @param email Atlassian account email for Basic auth; blank to send {@code apiToken} as a Bearer token instead
	 */
	public JiraTicketSystem(WebClient.Builder builder, String baseUrl, String email, String apiToken, Duration timeout) {
		if (apiToken == null || apiToken.isBlank()) {
			throw new IllegalArgumentException("Jira API token is required (agentic.jira.api-token)");
		}
		this.client = builder.build();
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.authorization = email == null || email.isBlank() ? "Bearer " + apiToken
				: "Basic " + Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
		this.timeout = timeout;
	}

	@Override
	public Mono<Ticket> fetch(String key) {
		requireKey(key);
		URI uri = URI.create(baseUrl + "/rest/api/3/issue/" + key + "?fields=summary,description,labels,project");
		return call(client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, authorization)
				.accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(JsonNode.class))
				.map(issue -> {
					JsonNode fields = issue.path("fields");
					Set<String> labels = new LinkedHashSet<>();
					fields.path("labels").forEach(label -> labels.add(label.asString()));
					return new Ticket(issue.path("key").asString(key), fields.path("project").path("key").asString(""),
							fields.path("summary").asString(key), AdfText.toText(fields.path("description")), labels,
							baseUrl + "/browse/" + key);
				});
	}

	@Override
	public Mono<Void> comment(String key, String text, String link) {
		requireKey(key);
		List<Object> inline = new ArrayList<>();
		inline.add(Map.of("type", "text", "text", text));
		if (link != null) {
			inline.add(Map.of("type", "text", "text", " "));
			inline.add(Map.of("type", "text", "text", link, "marks",
					List.of(Map.of("type", "link", "attrs", Map.of("href", link)))));
		}
		Map<String, Object> body = Map.of("body", Map.of("type", "doc", "version", 1, "content",
				List.of(Map.of("type", "paragraph", "content", inline))));
		URI uri = URI.create(baseUrl + "/rest/api/3/issue/" + key + "/comment");
		return call(client.post().uri(uri).header(HttpHeaders.AUTHORIZATION, authorization)
				.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue(body).retrieve()
				.bodyToMono(JsonNode.class)).then();
	}

	/** Issue keys are PROJECT-123; validating them keeps webhook input out of the URL path. */
	static void requireKey(String key) {
		if (key == null || !key.matches("[A-Z][A-Z0-9_]{0,63}-[0-9]{1,12}")) {
			throw new IllegalArgumentException("not a Jira issue key: " + key);
		}
	}

	private <T> Mono<T> call(Mono<T> request) {
		return request.timeout(timeout)
				.retryWhen(Retry.backoff(3, Duration.ofSeconds(2)).filter(e -> e instanceof WebClientResponseException w
						&& (w.getStatusCode().value() == 429 || w.getStatusCode().is5xxServerError())))
				.onErrorMap(WebClientResponseException.class, e -> new IllegalStateException("Jira API "
						+ e.getStatusCode().value() + " for " + (e.getRequest() == null ? "?" : e.getRequest().getURI().getPath())
						+ ": " + abbreviate(e.getResponseBodyAsString())));
	}

	private static String abbreviate(String body) {
		return body.length() <= 300 ? body : body.substring(0, 300) + "…";
	}
}
