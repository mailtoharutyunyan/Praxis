package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.scm.PullRequestFeedback;
import io.agenticsdlc.core.scm.PullRequestFeedback.CiFailure;
import io.agenticsdlc.core.scm.PullRequestFeedback.Comment;
import io.agenticsdlc.core.scm.PullRequestFeedback.Result;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * Code host webhooks that revise open pull requests (see {@link PullRequestFeedback}):
 * <ul>
 * <li>GitHub ({@code X-Hub-Signature-256}): {@code issue_comment}, {@code pull_request_review_comment} and
 * {@code pull_request_review} mentioning the bot; {@code workflow_run} completed with a failure.</li>
 * <li>GitLab ({@code X-Gitlab-Token}): {@code Note Hook} on merge requests mentioning the bot; failed
 * {@code Pipeline Hook}.</li>
 * </ul>
 * Everything else is acknowledged and ignored, so hosts do not retry or disable the hook.
 */
@RestController
class ScmWebhookController {

	private static final Logger log = LoggerFactory.getLogger(ScmWebhookController.class);

	private final ObjectProvider<PullRequestFeedback> feedback;
	private final ConnectorSettings settings;
	private final JsonMapper json;

	ScmWebhookController(ObjectProvider<PullRequestFeedback> feedback, ConnectorSettings settings, JsonMapper json) {
		this.feedback = feedback;
		this.settings = settings;
		this.json = json;
	}

	@PostMapping("/api/v1/webhooks/github")
	Mono<ResponseEntity<Map<String, Object>>> github(@RequestBody byte[] body,
			@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
			@RequestHeader(name = "X-GitHub-Event", required = false) String event) {
		if (!WebhookSignatures.validHubSignature(signature, body, settings.feedback().githubSecret())) {
			return unauthorized();
		}
		JsonNode payload = json.readTree(body);
		String action = payload.path("action").asString("");
		Mono<Result> result = switch (event == null ? "" : event) {
			case "issue_comment" -> action.equals("created") && payload.path("issue").has("pull_request")
					? comment(gitHubComment(payload, payload.path("comment"), payload.path("issue").path("pull_request")
							.path("html_url").asString(""), null))
					: ignored("not a new pull request comment");
			case "pull_request_review_comment" -> action.equals("created")
					? comment(gitHubComment(payload, payload.path("comment"), payload.path("pull_request").path("html_url")
							.asString(""), location(payload.path("comment").path("path").asString(null),
									payload.path("comment").path("line").asString(null))))
					: ignored("not a new review comment");
			case "pull_request_review" -> action.equals("submitted") && !payload.path("review").path("body").isNull()
					? comment(gitHubComment(payload, payload.path("review"), payload.path("pull_request").path("html_url")
							.asString(""), null))
					: ignored("not a submitted review with a body");
			case "workflow_run" -> {
				JsonNode run = payload.path("workflow_run");
				yield action.equals("completed") && "failure".equals(run.path("conclusion").asString(""))
						? ci(new CiFailure("github", run.path("id").asString(), run.path("head_branch").asString(""),
								run.path("head_sha").asString(""), run.path("html_url").asString(""),
								payload.path("repository").path("html_url").asString(null)))
						: ignored("not a failed workflow run");
			}
			default -> ignored("event " + event + " is not used");
		};
		return respond(result);
	}

	@PostMapping("/api/v1/webhooks/gitlab")
	Mono<ResponseEntity<Map<String, Object>>> gitlab(@RequestBody byte[] body,
			@RequestHeader(name = "X-Gitlab-Token", required = false) String token,
			@RequestHeader(name = "X-Gitlab-Event", required = false) String event) {
		if (!WebhookSignatures.validToken(token, settings.feedback().gitlabToken())) {
			return unauthorized();
		}
		JsonNode payload = json.readTree(body);
		JsonNode attributes = payload.path("object_attributes");
		Mono<Result> result = switch (event == null ? "" : event) {
			case "Note Hook" -> "MergeRequest".equals(attributes.path("noteable_type").asString(""))
					? comment(new Comment("gitlab", attributes.path("id").asString(),
							payload.path("merge_request").path("url").asString(""),
							payload.path("user").path("username").asString("unknown"),
							payload.path("user").path("id").asString(""), attributes.path("note").asString(""),
							location(attributes.path("position").path("new_path").asString(null),
									attributes.path("position").path("new_line").asString(null)),
							attributes.path("url").asString(null)))
					: ignored("not a merge request comment");
			case "Pipeline Hook" -> "failed".equals(attributes.path("status").asString(""))
					? ci(new CiFailure("gitlab", attributes.path("id").asString(), attributes.path("ref").asString(""),
							attributes.path("sha").asString(""), payload.path("project").path("web_url").asString("")
									+ "/-/pipelines/" + attributes.path("id").asString(),
							payload.path("project").path("web_url").asString(null)))
					: ignored("not a failed pipeline");
			default -> ignored("event " + event + " is not used");
		};
		return respond(result);
	}

	/** The bot's own comments never trigger it; GitHub marks app and bot accounts as type "Bot". */
	private Comment gitHubComment(JsonNode payload, JsonNode comment, String pullRequestUrl, String location) {
		String login = comment.path("user").path("login").asString("unknown");
		String body = "Bot".equals(comment.path("user").path("type").asString("")) ? "" : comment.path("body").asString("");
		return new Comment("github", comment.path("id").asString(), pullRequestUrl, login, login, body, location,
				comment.path("html_url").asString(null));
	}

	private static String location(String path, String line) {
		if (path == null || path.isBlank()) {
			return null;
		}
		return line == null || line.isBlank() || line.equals("null") ? path : path + ":" + line;
	}

	private Mono<Result> comment(Comment comment) {
		PullRequestFeedback service = feedback.getIfAvailable();
		return service == null ? ignored("publishing is not enabled") : service.onComment(comment);
	}

	private Mono<Result> ci(CiFailure failure) {
		PullRequestFeedback service = feedback.getIfAvailable();
		return service == null ? ignored("publishing is not enabled") : service.onCiFailure(failure);
	}

	private static Mono<Result> ignored(String reason) {
		return Mono.just(new Result.Ignored(reason));
	}

	private static Mono<ResponseEntity<Map<String, Object>>> respond(Mono<Result> result) {
		return result.map(outcome -> switch (outcome) {
			case Result.Revising revising -> ResponseEntity.status(HttpStatus.ACCEPTED)
					.body(Map.<String, Object>of("runId", revising.runId().toString(), "revising", true));
			case Result.Ignored ignored -> {
				log.debug("code host webhook ignored: {}", ignored.reason());
				yield ResponseEntity.ok(Map.<String, Object>of("ignored", ignored.reason()));
			}
		}).onErrorResume(IllegalStateException.class, e -> Mono.just(ResponseEntity.ok(Map.of("ignored", e.getMessage()))));
	}

	private static Mono<ResponseEntity<Map<String, Object>>> unauthorized() {
		return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid signature")));
	}
}
