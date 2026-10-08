package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.SlackRuntime;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The {@code /agentic} Slack slash command: {@code /agentic [repository URL] <what to do>}. Requests are verified
 * with the app's signing secret (v0 HMAC over timestamp and body, at most five minutes old) and must come from an
 * allowed channel or user. The run gets a thread in
 * the channel, and progress is posted there. Slack text is untrusted, like any ticket.
 */
@RestController
class SlackCommandController {

	private static final Logger log = LoggerFactory.getLogger(SlackCommandController.class);
	private static final long MAX_AGE_SECONDS = 300;

	private final SlackRuntime slack;
	private final TaskIntake intake;
	private final ConnectorSettings connectors;
	private final Clock clock;

	SlackCommandController(SlackRuntime slack, TaskIntake intake, ConnectorSettings connectors, Clock clock) {
		this.slack = slack;
		this.intake = intake;
		this.connectors = connectors;
		this.clock = clock;
	}

	@PostMapping("/api/v1/webhooks/slack/commands")
	Mono<ResponseEntity<Map<String, Object>>> command(ServerHttpRequest request,
			@RequestHeader(name = "X-Slack-Request-Timestamp", required = false) String timestamp,
			@RequestHeader(name = "X-Slack-Signature", required = false) String signature) {
		// The signature covers the raw form body, so it is read as bytes rather than bound as form data.
		return DataBufferUtils.join(request.getBody(), 64 * 1024).map(buffer -> {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			DataBufferUtils.release(buffer);
			return bytes;
		}).defaultIfEmpty(new byte[0]).flatMap(body -> command(body, timestamp, signature));
	}

	private Mono<ResponseEntity<Map<String, Object>>> command(byte[] body, String timestamp, String signature) {
		SlackRuntime.Active active = slack.current().orElse(null);
		if (active == null) {
			return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Slack is not configured")));
		}
		byte[] raw = body;
		if (!verified(active.signingSecret(), timestamp, signature, raw)) {
			return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid signature")));
		}
		Map<String, String> form = form(new String(raw, StandardCharsets.UTF_8));
		String channel = form.getOrDefault("channel_id", "");
		if (!active.settings().allows(channel, form.getOrDefault("user_id", ""))) {
			log.info("Slack command refused: channel {} and user {} are not allowed", channel, form.get("user_id"));
			return reply("This channel and user are not allowed to start runs. An admin can allow them in Settings → Slack.");
		}
		String text = form.getOrDefault("text", "").strip();
		Request request = parse(text, active);
		if (request == null) {
			return reply("Usage: `/agentic [repository URL] what to do`, e.g. `/agentic https://github.com/acme/shop.git "
					+ "Add a health endpoint`." + (active.defaultRepository().isBlank() ? ""
							: " Without a URL, " + active.defaultRepository() + " is used."));
		}
		String user = "slack:" + form.getOrDefault("user_id", "unknown");
		String title = request.task().lines().findFirst().orElse(request.task());
		String opening = "<@" + form.getOrDefault("user_id", "") + "> asked: " + escape(abbreviate(request.task(), 300))
				+ "\nRepository: " + request.repository().cloneUrl();
		return active.threads().start(channel, opening)
				.flatMap(thread -> intake.submit(new NewTask(TaskOrigin.SLACK, thread, abbreviate(title, 120),
						request.task(), request.repository(), null, user, "slack:" + form.getOrDefault("trigger_id", thread))))
				.flatMap(submission -> {
					String id = submission.view().run().id().toString();
					String link = active.runLinkBase().isBlank() ? "run " + id : active.runLinkBase() + id;
					return reply("Started " + link + ". Progress is posted in the thread.");
				})
				.onErrorResume(e -> {
					log.warn("Slack command failed: {}", e.getMessage());
					return reply("Could not start the run: " + abbreviate(String.valueOf(e.getMessage()), 300)
							+ (String.valueOf(e.getMessage()).contains("not_in_channel")
									? " Invite the app to this channel first." : ""));
				});
	}

	record Request(RepositoryRef repository, String task) {
	}

	private Request parse(String text, SlackRuntime.Active active) {
		if (text.isEmpty() || text.equalsIgnoreCase("help")) {
			return null;
		}
		String first = text.split("\\s+", 2)[0];
		// Slack wraps links as <https://…> or <https://…|label>.
		String candidate = first.startsWith("<") && first.endsWith(">") ? first.substring(1, first.length() - 1).split("\\|")[0]
				: first;
		String url;
		String task;
		if (candidate.matches("https?://\\S+")) {
			url = candidate;
			task = text.substring(first.length()).strip();
		}
		else {
			url = active.defaultRepository();
			task = text;
		}
		if (url.isBlank() || task.isBlank()) {
			return null;
		}
		URI uri = URI.create(url);
		return new Request(new RepositoryRef(kind(uri.getHost(), active.defaultKind()), uri), task);
	}

	/** The provider for a host: as configured for it, else by well-known host, else the connector's default. */
	private ScmKind kind(String host, String defaultKind) {
		String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
		return connectors.gitHosts().stream().filter(g -> g.host().equalsIgnoreCase(h)).findFirst()
				.map(g -> ScmKind.valueOf(g.kind()))
				.orElseGet(() -> switch (h) {
					case "github.com" -> ScmKind.GITHUB;
					case "gitlab.com" -> ScmKind.GITLAB;
					case "bitbucket.org" -> ScmKind.BITBUCKET;
					case "dev.azure.com" -> ScmKind.AZURE_DEVOPS;
					default -> ScmKind.valueOf(defaultKind.isBlank() ? "GITHUB" : defaultKind);
				});
	}

	private boolean verified(String secret, String timestamp, String signature, byte[] body) {
		if (secret == null || secret.isBlank() || timestamp == null || signature == null || !signature.startsWith("v0=")) {
			return false;
		}
		long ts;
		try {
			ts = Long.parseLong(timestamp.strip());
		}
		catch (NumberFormatException e) {
			return false;
		}
		if (Math.abs(clock.instant().getEpochSecond() - ts) > MAX_AGE_SECONDS) {
			return false;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update(("v0:" + ts + ":").getBytes(StandardCharsets.UTF_8));
			byte[] expected = mac.doFinal(body);
			return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature.substring(3).strip()));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
			return false;
		}
	}

	private static Map<String, String> form(String body) {
		Map<String, String> values = new HashMap<>();
		for (String pair : body.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int eq = pair.indexOf('=');
			String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			values.putIfAbsent(key, value);
		}
		return values;
	}

	private static Mono<ResponseEntity<Map<String, Object>>> reply(String text) {
		return Mono.just(ResponseEntity.ok(Map.of("response_type", "ephemeral", "text", text)));
	}

	/** Echoed user text must not become mentions such as {@code <!channel>}. */
	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String abbreviate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "…";
	}
}
