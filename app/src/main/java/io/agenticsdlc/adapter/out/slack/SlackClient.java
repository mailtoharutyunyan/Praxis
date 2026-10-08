package io.agenticsdlc.adapter.out.slack;

import io.agenticsdlc.core.intake.Ticket;
import io.agenticsdlc.core.intake.TicketSystem;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Slack Web API with a bot token. A run started from Slack is tracked by its thread, {@code <channel>:<ts>}; status
 * updates are replies in that thread.
 */
public class SlackClient implements TicketSystem {

	private final WebClient client;
	private final String apiUrl;
	private final String botToken;
	private final Duration timeout;

	public SlackClient(WebClient.Builder builder, String apiUrl, String botToken, Duration timeout) {
		if (botToken == null || botToken.isBlank()) {
			throw new IllegalArgumentException("Slack bot token is required");
		}
		this.client = builder.build();
		this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
		this.botToken = botToken;
		this.timeout = timeout;
	}

	/** Posts a message, in a thread when {@code threadTs} is set; emits the thread reference of the message. */
	public Mono<String> postMessage(String channel, String threadTs, String text) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("channel", channel);
		body.put("text", text);
		// Links to runs stay plain links, not previews.
		body.put("unfurl_links", false);
		if (threadTs != null) {
			body.put("thread_ts", threadTs);
		}
		return client.post().uri(URI.create(apiUrl + "/chat.postMessage"))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + botToken)
				.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue(body).retrieve()
				.bodyToMono(JsonNode.class).timeout(timeout)
				.flatMap(response -> response.path("ok").asBoolean(false)
						? Mono.just(channel + ":" + response.path("ts").asString())
						// Slack reports failures in the body with HTTP 200, e.g. not_in_channel or invalid_auth.
						: Mono.error(new IllegalStateException("Slack chat.postMessage failed: "
								+ response.path("error").asString("unknown error"))));
	}

	/** Slack threads have no ticket to read back; runs from Slack are created by the slash command. */
	@Override
	public Mono<Ticket> fetch(String key) {
		return Mono.error(new UnsupportedOperationException("Slack threads cannot be fetched"));
	}

	@Override
	public Mono<Void> comment(String key, String text, String link) {
		ThreadRef thread = ThreadRef.parse(key);
		return postMessage(thread.channel(), thread.ts(), link == null ? text : text + "\n" + link).then();
	}

	/** {@code C0123ABC:1712345678.123456}; validated so stored references cannot be bent into other API calls. */
	public record ThreadRef(String channel, String ts) {

		public static ThreadRef parse(String key) {
			if (key == null || !key.matches("[A-Z0-9]{2,32}:[0-9]{1,12}\\.[0-9]{1,9}")) {
				throw new IllegalArgumentException("not a Slack thread reference: " + key);
			}
			int colon = key.indexOf(':');
			return new ThreadRef(key.substring(0, colon), key.substring(colon + 1));
		}
	}
}
