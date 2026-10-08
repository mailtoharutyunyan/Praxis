package io.agenticsdlc.core.agent;

import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The agent loop (ADR-0003): model turn → execute requested tools → feed results back, until the model answers
 * without tool calls or a limit stops it. Every turn, tool call and result is recorded as a run event, and limits
 * are enforced between turns:
 * <ul>
 * <li>{@code maxTurns} model calls;</li>
 * <li>a token budget for this loop (the rest of the run's budget);</li>
 * <li>stuck detection: the same tool call repeated {@code maxRepeats} times in a row.</li>
 * </ul>
 * Context is kept small by clearing the content of old tool results (the model can re-read files).
 */
public final class AgentLoop {

	/** Recent tool-result messages kept verbatim; older ones are reduced to a short stub. */
	static final int KEEP_RECENT_TOOL_RESULTS = 6;
	static final int CLEARED_RESULT_CHARS = 300;
	static final int EVENT_PREVIEW_CHARS = 2_000;

	private final AgentModel model;
	private final Map<String, AgentTool> tools;
	private final Limits limits;

	/**
	 * @param maxTurns model calls per loop
	 * @param maxOutputTokens per model call
	 * @param maxToolResultChars longest tool output passed to the model; the tail is kept
	 * @param maxRepeats identical consecutive tool calls tolerated before the loop is considered stuck
	 */
	public record Limits(int maxTurns, int maxOutputTokens, int maxToolResultChars, int maxRepeats) {
		public Limits {
			if (maxTurns < 1 || maxOutputTokens < 1 || maxToolResultChars < 100 || maxRepeats < 2) {
				throw new IllegalArgumentException("invalid agent loop limits");
			}
		}
	}

	public enum Stop {
		/** The model finished: it answered without requesting tools. */
		COMPLETED,
		MAX_TURNS,
		BUDGET_EXHAUSTED,
		STUCK
	}

	public record Outcome(Stop stop, String finalText, Usage usage, int turns) {
		public boolean completed() {
			return stop == Stop.COMPLETED;
		}
	}

	public AgentLoop(AgentModel model, List<AgentTool> tools, Limits limits) {
		this.model = Objects.requireNonNull(model, "model");
		this.limits = Objects.requireNonNull(limits, "limits");
		this.tools = tools.stream().collect(Collectors.toMap(t -> t.spec().name(), Function.identity(), (a, b) -> {
			throw new IllegalArgumentException("duplicate tool " + a.spec().name());
		}, LinkedHashMap::new));
	}

	/**
	 * @param actor event actor, e.g. {@code agent:coder}
	 * @param tokenBudget tokens this loop may still spend; the loop stops once exceeded
	 */
	public Mono<Outcome> run(StageContext context, String actor, String system, String task, long tokenBudget) {
		List<ToolSpec> specs = tools.values().stream().map(AgentTool::spec).toList();
		State initial = new State(List.of(new AgentMessage.User(task)), Usage.ZERO, 0, null, 0);
		return turn(context, actor, system, specs, initial, tokenBudget);
	}

	private Mono<Outcome> turn(StageContext context, String actor, String system, List<ToolSpec> specs, State state,
			long tokenBudget) {
		ModelRequest request = new ModelRequest(system, compact(state.messages()), specs, limits.maxOutputTokens());
		return model.complete(request).flatMap(reply -> {
			context.recordSpend(reply.usage());
			Usage usage = state.usage().plus(reply.usage());
			int turns = state.turns() + 1;
			Mono<Void> said = reply.text().isBlank() ? Mono.empty()
					: context.emit(RunEventType.AGENT_MESSAGE, actor, payload("text", reply.text(), "model", model.id(),
							"turn", turns));
			if (reply.toolCalls().isEmpty()) {
				return said.thenReturn(new Outcome(Stop.COMPLETED, reply.text(), usage, turns));
			}
			String signature = reply.toolCalls().stream().map(c -> c.name() + c.arguments()).collect(Collectors.joining("|"));
			int repeats = signature.equals(state.lastSignature()) ? state.repeats() + 1 : 1;
			if (repeats >= limits.maxRepeats()) {
				return said.thenReturn(new Outcome(Stop.STUCK, "repeated the same tool call " + repeats + " times: "
						+ signature, usage, turns));
			}
			return said.then(Flux.fromIterable(reply.toolCalls()).concatMap(call -> invoke(context, actor, call))
					.collectList())
					.flatMap(results -> {
						List<AgentMessage> messages = new ArrayList<>(state.messages());
						messages.add(new AgentMessage.Assistant(reply.text(), reply.toolCalls(), reply.nativeMessage()));
						messages.add(new AgentMessage.ToolResults(results));
						if (usage.totalTokens() > tokenBudget) {
							return Mono.just(new Outcome(Stop.BUDGET_EXHAUSTED, "token budget for this stage exhausted",
									usage, turns));
						}
						if (turns >= limits.maxTurns()) {
							return Mono.just(new Outcome(Stop.MAX_TURNS, "reached " + turns + " turns", usage, turns));
						}
						return turn(context, actor, system, specs,
								new State(messages, usage, turns, signature, repeats), tokenBudget);
					});
		});
	}

	private Mono<ToolResult> invoke(StageContext context, String actor, ToolCall call) {
		AgentTool tool = tools.get(call.name());
		Mono<String> execution = tool == null
				? Mono.error(new ToolException("unknown tool '" + call.name() + "'; available: " + tools.keySet()))
				: Mono.defer(() -> tool.execute(context.run().id(), call));
		return context.emit(RunEventType.TOOL_CALLED, actor, payload("tool", call.name(), "callId", call.id(),
						"arguments", preview(call.rawArguments())))
				.then(execution
						.map(content -> new ToolResult(call.id(), call.name(), tail(content), false))
						.onErrorResume(ToolException.class,
								e -> Mono.just(new ToolResult(call.id(), call.name(), "Error: " + e.getMessage(), true))))
				.flatMap(result -> context.emit(RunEventType.TOOL_RESULT, actor, payload("tool", call.name(),
						"callId", call.id(), "error", result.error(), "output", preview(result.content())))
						.thenReturn(result));
	}

	/** Old tool outputs are replaced by a short stub; the model can call the tool again if it needs them. */
	static List<AgentMessage> compact(List<AgentMessage> messages) {
		long toolResultMessages = messages.stream().filter(AgentMessage.ToolResults.class::isInstance).count();
		long toClear = toolResultMessages - KEEP_RECENT_TOOL_RESULTS;
		if (toClear <= 0) {
			return messages;
		}
		List<AgentMessage> compacted = new ArrayList<>(messages.size());
		long seen = 0;
		for (AgentMessage message : messages) {
			if (message instanceof AgentMessage.ToolResults results && seen++ < toClear) {
				compacted.add(new AgentMessage.ToolResults(results.results().stream()
						.map(r -> r.content().length() <= CLEARED_RESULT_CHARS ? r
								: new ToolResult(r.callId(), r.name(), r.content().substring(0, CLEARED_RESULT_CHARS)
										+ "\n[older output cleared to save context; call the tool again if needed]",
										r.error()))
						.toList()));
			}
			else {
				compacted.add(message);
			}
		}
		return compacted;
	}

	private String tail(String content) {
		int max = limits.maxToolResultChars();
		return content.length() <= max ? content
				: "[output truncated, showing last " + max + " characters]\n" + content.substring(content.length() - max);
	}

	private static String preview(String text) {
		return text.length() <= EVENT_PREVIEW_CHARS ? text : text.substring(0, EVENT_PREVIEW_CHARS) + "…";
	}

	private static Map<String, Object> payload(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	private record State(List<AgentMessage> messages, Usage usage, int turns, String lastSignature, int repeats) {
	}
}
