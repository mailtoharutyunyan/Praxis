package io.agenticsdlc.adapter.out.cli;

import io.agenticsdlc.core.agent.AgentLoop;
import io.agenticsdlc.core.agent.AgentLoop.Outcome;
import io.agenticsdlc.core.agent.AgentLoop.Stop;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.workspace.CommandResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one call's Claude Code {@code stream-json} output, a JSON message per line, and records what an
 * {@link AgentLoop} would: {@code AGENT_MESSAGE} for text, {@code TOOL_CALLED} and {@code TOOL_RESULT} for tool use,
 * and usage as it is spent (ADR-0008). The last line is the {@code result} message with the final answer, the turns,
 * the usage and the cost.
 * <p>
 * Assistant messages come once per content block and repeat their API message's usage, so usage is counted once per
 * message id; their output token counts are placeholders, so the result's usage is the one that counts when it comes.
 * Every recorded text has the CLI's credential replaced, in case the agent printed it.
 */
final class StreamJsonTranscript {

	private static final Logger log = LoggerFactory.getLogger(StreamJsonTranscript.class);
	static final String REDACTED = "[REDACTED]";

	private final StageContext context;
	private final String actor;
	private final String secret;
	private final long tokenBudget;
	private final JsonMapper json;
	private final Map<String, Usage> messages = new LinkedHashMap<>();
	private final Map<String, String> toolNames = new HashMap<>();
	private Usage recorded = Usage.ZERO;
	private String model = "claude-code";
	private JsonNode result;

	/** @param secret replaced in everything recorded; blank for none */
	StreamJsonTranscript(StageContext context, String actor, String secret, long tokenBudget, JsonMapper json) {
		this.context = context;
		this.actor = actor;
		this.secret = secret == null ? "" : secret;
		this.tokenBudget = tokenBudget;
		this.json = json;
	}

	/** Records one line of output; lines that are not JSON messages are skipped. */
	Mono<Void> accept(String line) {
		if (line.isBlank()) {
			return Mono.empty();
		}
		JsonNode message;
		try {
			message = json.readTree(line);
		}
		catch (JacksonException e) {
			log.debug("skipping output that is not a stream-json message: {}", preview(redact(line)));
			return Mono.empty();
		}
		return switch (message.path("type").asString("")) {
			case "system" -> {
				if ("init".equals(message.path("subtype").asString(""))) {
					model = "claude-code/" + message.path("model").asString("default");
				}
				yield Mono.empty();
			}
			case "assistant" -> assistant(message.path("message"));
			case "user" -> toolResults(message.path("message"));
			case "result" -> {
				result = message;
				yield Mono.empty();
			}
			default -> Mono.empty();
		};
	}

	private Mono<Void> assistant(JsonNode message) {
		String id = message.path("id").asString("");
		if (!id.isEmpty() && message.path("usage").isObject()) {
			messages.put(id, usage(message.path("usage"), BigDecimal.ZERO));
			spend(streamed());
		}
		List<Mono<Void>> events = new ArrayList<>();
		for (JsonNode block : message.path("content")) {
			switch (block.path("type").asString("")) {
				case "text" -> {
					String text = block.path("text").asString("");
					if (!text.isBlank()) {
						events.add(context.emit(RunEventType.AGENT_MESSAGE, actor, payload("text", redact(text), "model",
								model, "turn", messages.size())));
					}
				}
				case "tool_use" -> {
					String callId = block.path("id").asString("");
					String name = block.path("name").asString("?");
					toolNames.put(callId, name);
					events.add(context.emit(RunEventType.TOOL_CALLED, actor, payload("tool", name, "callId", callId,
							"arguments", preview(redact(block.path("input").toString())))));
				}
				default -> {
					// thinking and other blocks are not recorded
				}
			}
		}
		return Flux.concat(events).then();
	}

	private Mono<Void> toolResults(JsonNode message) {
		List<Mono<Void>> events = new ArrayList<>();
		for (JsonNode block : message.path("content")) {
			if ("tool_result".equals(block.path("type").asString(""))) {
				String callId = block.path("tool_use_id").asString("");
				events.add(context.emit(RunEventType.TOOL_RESULT, actor, payload("tool", toolNames.getOrDefault(callId, "?"),
						"callId", callId, "error", block.path("is_error").asBoolean(false), "output",
						preview(redact(text(block.path("content")))))));
			}
		}
		return Flux.concat(events).then();
	}

	/** Whether the call has spent more tokens than it may; it is then stopped. */
	boolean overBudget() {
		return streamed().totalTokens() > tokenBudget;
	}

	/** The outcome once the output has ended, or the call was stopped for its budget. */
	Outcome outcome() {
		Usage usage = finalUsage();
		int turns = result == null ? messages.size() : result.path("num_turns").asInt(messages.size());
		if (result == null) {
			return overBudget() ? new Outcome(Stop.BUDGET_EXHAUSTED, "token budget for this stage exhausted", usage, turns)
					: new Outcome(Stop.FAILED, "Claude Code ended without a result", usage, turns);
		}
		String subtype = result.path("subtype").asString("");
		Stop stop = switch (subtype) {
			case "success" -> result.path("is_error").asBoolean(false) ? Stop.FAILED : Stop.COMPLETED;
			case "error_max_turns" -> Stop.MAX_TURNS;
			case "error_max_budget_usd" -> Stop.BUDGET_EXHAUSTED;
			default -> Stop.FAILED;
		};
		String text = result.path("result").asString("");
		if (stop != Stop.COMPLETED && text.isBlank()) {
			List<String> errors = new ArrayList<>();
			result.path("errors").forEach(error -> errors.add(error.isString() ? error.asString() : error.toString()));
			text = switch (stop) {
				case MAX_TURNS -> "reached " + turns + " turns";
				case BUDGET_EXHAUSTED -> "the run's cost limit was reached";
				default -> "Claude Code reported " + (subtype.isEmpty() ? "an error" : subtype)
						+ (errors.isEmpty() ? "" : ": " + String.join("; ", errors));
			};
		}
		return new Outcome(stop, redact(text), usage, turns);
	}

	/** The outcome when the command failed: what the result said if there was one, else the exit and its error output. */
	Outcome failed(CommandResult command) {
		if (result != null) {
			return outcome();
		}
		String reason = command.timedOut() ? "Claude Code timed out after " + command.took().toSeconds() + "s"
				: "Claude Code exited with code " + command.exitCode()
						+ (command.exitCode() == 127 ? " (not installed, or built for another C library: alpine images "
								+ "need the musl build)" : "");
		String stderr = redact(command.tail(1_000)).strip();
		return new Outcome(Stop.FAILED, stderr.isEmpty() ? reason : reason + ": " + stderr, finalUsage(), messages.size());
	}

	/** The result's usage and cost where known, never less than was streamed; anything not yet recorded is recorded. */
	private Usage finalUsage() {
		Usage streamed = streamed();
		Usage total = result == null ? streamed : max(streamed, usage(result.path("usage"),
				result.path("total_cost_usd").isNumber() ? result.path("total_cost_usd").decimalValue() : BigDecimal.ZERO));
		spend(total);
		return total;
	}

	private Usage streamed() {
		return messages.values().stream().reduce(Usage.ZERO, Usage::plus);
	}

	/** Records the part of {@code total} not recorded yet, so a stage that fails still accounts for it. */
	private void spend(Usage total) {
		Usage delta = new Usage(Math.max(0, total.inputTokens() - recorded.inputTokens()),
				Math.max(0, total.outputTokens() - recorded.outputTokens()),
				Math.max(0, total.cacheReadTokens() - recorded.cacheReadTokens()),
				Math.max(0, total.cacheWriteTokens() - recorded.cacheWriteTokens()),
				Math.max(0, total.costMicroUsd() - recorded.costMicroUsd()));
		if (delta.totalTokens() > 0 || delta.costMicroUsd() > 0) {
			context.recordSpend(delta);
			recorded = recorded.plus(delta);
		}
	}

	private static Usage usage(JsonNode usage, BigDecimal costUsd) {
		return new Usage(Math.max(0, usage.path("input_tokens").asLong(0)), Math.max(0, usage.path("output_tokens").asLong(0)),
				Math.max(0, usage.path("cache_read_input_tokens").asLong(0)),
				Math.max(0, usage.path("cache_creation_input_tokens").asLong(0)),
				Math.max(0, costUsd.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValue()));
	}

	private static Usage max(Usage a, Usage b) {
		return new Usage(Math.max(a.inputTokens(), b.inputTokens()), Math.max(a.outputTokens(), b.outputTokens()),
				Math.max(a.cacheReadTokens(), b.cacheReadTokens()), Math.max(a.cacheWriteTokens(), b.cacheWriteTokens()),
				Math.max(a.costMicroUsd(), b.costMicroUsd()));
	}

	/** Tool result content: a string, or content blocks of which text is kept. */
	private static String text(JsonNode content) {
		if (content.isString()) {
			return content.asString();
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode block : content) {
			String type = block.path("type").asString("");
			text.append(type.equals("text") ? block.path("text").asString("") : "[" + type + "]").append('\n');
		}
		return text.toString().strip();
	}

	String redact(String text) {
		return secret.isBlank() || text == null ? text : text.replace(secret, REDACTED);
	}

	private static String preview(String text) {
		return text.length() <= AgentLoop.EVENT_PREVIEW_CHARS ? text
				: text.substring(0, AgentLoop.EVENT_PREVIEW_CHARS) + "…";
	}

	private static Map<String, Object> payload(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}
}
