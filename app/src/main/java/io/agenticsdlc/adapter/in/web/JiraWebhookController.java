package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.core.intake.TicketIntake;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jira triggers. Two senders are accepted:
 * <ul>
 * <li>Jira admin webhooks ({@code jira:issue_created} / {@code jira:issue_updated}), authenticated by
 * {@code X-Hub-Signature} HMAC over the raw body; a run starts when an issue is created with the trigger label or the
 * label is added. Retries carry the same {@code X-Atlassian-Webhook-Identifier} and map to the same run.</li>
 * <li>Jira Automation "Send web request" with header {@code X-Agentic-Webhook-Token} (a static secret; not
 * {@code Authorization}, which the API reserves for user JWTs) and body {@code {"key": "{{issue.key}}"}}.</li>
 * </ul>
 * The body only identifies the issue; its content is read back from Jira by {@link TicketIntake}.
 */
@RestController
@ConditionalOnBooleanProperty("agentic.jira.enabled")
class JiraWebhookController {

	private static final Logger log = LoggerFactory.getLogger(JiraWebhookController.class);

	private final TicketIntake intake;
	private final AgenticProperties.Jira settings;
	private final JsonMapper json;

	JiraWebhookController(TicketIntake intake, AgenticProperties properties, JsonMapper json) {
		this.intake = intake;
		this.settings = properties.jira();
		this.json = json;
	}

	@PostMapping("/api/v1/webhooks/jira")
	Mono<ResponseEntity<Map<String, Object>>> receive(@RequestBody byte[] body,
			@RequestHeader(name = "X-Hub-Signature", required = false) String signature,
			@RequestHeader(name = "X-Agentic-Webhook-Token", required = false) String token,
			@RequestHeader(name = "X-Atlassian-Webhook-Identifier", required = false) String deliveryId) {
		boolean signed = WebhookSignatures.validHubSignature(signature, body, settings.webhookSecret());
		boolean automation = !signed && WebhookSignatures.validToken(token, settings.automationToken());
		if (!signed && !automation) {
			return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid signature")));
		}
		JsonNode event = json.readTree(body);
		TicketIntake.Trigger trigger = signed ? fromWebhook(event, deliveryId) : fromAutomation(event);
		if (trigger == null) {
			return Mono.just(ResponseEntity.ok(Map.of("ignored", "not a trigger for label '" + settings.triggerLabel() + "'")));
		}
		return intake.onTrigger(trigger).map(result -> switch (result) {
			case TicketIntake.Result.Started started -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.<String, Object>of(
					"runId", started.submission().view().run().id().toString(), "created", started.submission().created()));
			case TicketIntake.Result.Ignored ignored -> {
				log.info("Jira trigger for {} ignored: {}", trigger.ticketKey(), ignored.reason());
				yield ResponseEntity.ok(Map.<String, Object>of("ignored", ignored.reason()));
			}
		});
	}

	private TicketIntake.Trigger fromWebhook(JsonNode event, String deliveryId) {
		String type = event.path("webhookEvent").asString("");
		String key = event.path("issue").path("key").asString(null);
		if (key == null) {
			return null;
		}
		boolean triggered = switch (type) {
			case "jira:issue_created" -> hasLabel(event.path("issue").path("fields").path("labels"));
			case "jira:issue_updated" -> labelAdded(event.path("changelog").path("items"));
			default -> false;
		};
		if (!triggered) {
			return null;
		}
		String eventId = deliveryId != null && !deliveryId.isBlank() ? deliveryId
				: event.path("timestamp").asString(String.valueOf(System.currentTimeMillis()));
		return new TicketIntake.Trigger(key, eventId, event.path("user").path("accountId").asString(null));
	}

	private TicketIntake.Trigger fromAutomation(JsonNode event) {
		String key = event.path("key").asString(null);
		if (key == null) {
			return null;
		}
		String eventId = event.path("eventId").asString(String.valueOf(System.currentTimeMillis()));
		return new TicketIntake.Trigger(key, eventId, event.path("actor").asString("automation"));
	}

	private boolean hasLabel(JsonNode labels) {
		String wanted = settings.triggerLabel().toLowerCase(Locale.ROOT);
		for (JsonNode label : labels) {
			if (label.asString("").toLowerCase(Locale.ROOT).equals(wanted)) {
				return true;
			}
		}
		return false;
	}

	/** Changelog label values are space-separated full lists; the label is added if it is in "to" but not "from". */
	private boolean labelAdded(JsonNode items) {
		String wanted = settings.triggerLabel().toLowerCase(Locale.ROOT);
		for (JsonNode item : items) {
			if ("labels".equals(item.path("field").asString(""))) {
				return words(item.path("toString").asString("")).contains(wanted)
						&& !words(item.path("fromString").asString("")).contains(wanted);
			}
		}
		return false;
	}

	private static Set<String> words(String list) {
		return new HashSet<>(Arrays.asList(list.toLowerCase(Locale.ROOT).trim().split("\\s+")));
	}
}
