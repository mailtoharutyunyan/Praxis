package io.agenticsdlc.adapter.out.llm;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.core.agent.AgentMessage;
import io.agenticsdlc.core.agent.AgentModel;
import io.agenticsdlc.core.agent.ModelReply;
import io.agenticsdlc.core.agent.ModelRequest;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.agent.ToolSpec;
import io.agenticsdlc.core.domain.Usage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AgentModel} on a Spring AI {@link ChatModel}. Tools are passed as definitions only: Spring AI 2.0 never
 * executes tools inside {@code ChatModel.call}, so {@code AgentLoop} stays in control (ADR-0003).
 * <p>
 * Notes from the 2.0.1 source: options on a {@link Prompt} replace the model defaults, so per-call options start from
 * {@code getOptions().mutate()}; Anthropic returns thinking in extra generations and keeps signed thinking blocks
 * only on its own assistant message type, so the reply is read from the last generation and that message object is
 * replayed unchanged in later turns.
 * <p>
 * Calls get an overall timeout, and transient failures (rate limits, overload, 5xx, timeouts, I/O) are retried with
 * jittered exponential backoff beyond the provider SDK's own short retries, so a busy provider delays a run instead
 * of escalating it to a human.
 */
class SpringAiAgentModel implements AgentModel {

	private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
	};
	private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
	private static final Logger log = LoggerFactory.getLogger(SpringAiAgentModel.class);

	/** How each model call is bounded and retried. */
	record CallPolicy(Duration timeout, int retries, Duration firstBackoff, Duration maxBackoff) {
		static final CallPolicy DEFAULT = new CallPolicy(Duration.ofMinutes(10), 4, Duration.ofSeconds(5),
				Duration.ofMinutes(2));
	}

	private final String id;
	private final ChatModel chatModel;
	private final AgenticProperties.Pricing pricing;
	private final JsonMapper json;
	private final CallPolicy policy;

	SpringAiAgentModel(String id, ChatModel chatModel, AgenticProperties.Pricing pricing, JsonMapper json) {
		this(id, chatModel, pricing, json, CallPolicy.DEFAULT);
	}

	SpringAiAgentModel(String id, ChatModel chatModel, AgenticProperties.Pricing pricing, JsonMapper json,
			CallPolicy policy) {
		this.id = id;
		this.chatModel = chatModel;
		this.pricing = pricing;
		this.json = json;
		this.policy = policy;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public Mono<ModelReply> complete(ModelRequest request) {
		return Mono.fromCallable(() -> chatModel.call(prompt(request)))
				.subscribeOn(Schedulers.boundedElastic())
				.timeout(policy.timeout())
				.retryWhen(Retry.backoff(policy.retries(), policy.firstBackoff())
						.maxBackoff(policy.maxBackoff())
						.jitter(0.5)
						.filter(SpringAiAgentModel::transientFailure)
						.doBeforeRetry(signal -> log.warn("model {} call failed ({}), retry {} of {}", id,
								signal.failure().toString(), signal.totalRetries() + 1, policy.retries()))
						.onRetryExhaustedThrow((spec, signal) -> signal.failure()))
				.map(this::reply);
	}

	/** Worth retrying: rate limits, overload, server errors, timeouts and I/O failures, from any provider SDK. */
	static boolean transientFailure(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof TransientAiException || t instanceof IOException || t instanceof TimeoutException
					|| t instanceof com.anthropic.errors.AnthropicIoException
					|| t instanceof com.anthropic.errors.AnthropicRetryableException
					|| t instanceof com.openai.errors.OpenAIIoException
					|| t instanceof com.openai.errors.OpenAIRetryableException
					|| t instanceof com.google.genai.errors.GenAiIOException) {
				return true;
			}
			if (t instanceof com.anthropic.errors.AnthropicServiceException e && retryableStatus(e.statusCode())
					|| t instanceof com.openai.errors.OpenAIServiceException o && retryableStatus(o.statusCode())
					|| t instanceof com.google.genai.errors.ApiException g && retryableStatus(g.code())
					|| t instanceof software.amazon.awssdk.core.exception.SdkException s && s.retryable()) {
				return true;
			}
		}
		return false;
	}

	private static boolean retryableStatus(int status) {
		return status == 408 || status == 409 || status == 429 || status >= 500;
	}

	Prompt prompt(ModelRequest request) {
		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(request.system()));
		for (AgentMessage message : request.messages()) {
			messages.add(switch (message) {
				case AgentMessage.User user -> new UserMessage(user.text());
				case AgentMessage.Assistant assistant -> assistant.nativeMessage() instanceof AssistantMessage original
						? original
						: AssistantMessage.builder()
								.content(assistant.text())
								.toolCalls(assistant.toolCalls().stream()
										.map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.rawArguments()))
										.toList())
								.build();
				case AgentMessage.ToolResults results -> ToolResponseMessage.builder()
						.responses(results.results().stream()
								.map(r -> new ToolResponseMessage.ToolResponse(r.callId(), r.name(), r.content()))
								.toList())
						.build();
			});
		}
		List<ToolCallback> tools = request.tools().stream().map(DefinitionOnlyTool::new).map(ToolCallback.class::cast)
				.toList();
		ToolCallingChatOptions options = ((ToolCallingChatOptions) chatModel.getOptions()).mutate()
				.toolCallbacks(tools)
				.build();
		return new Prompt(messages, options);
	}

	ModelReply reply(ChatResponse response) {
		List<Generation> generations = response.getResults();
		if (generations == null || generations.isEmpty()) {
			throw new IllegalStateException("model " + id + " returned no output");
		}
		// The answer is the generation carrying tool calls, else the last one (earlier ones may be thinking only).
		Generation chosen = generations.stream()
				.filter(g -> g.getOutput() != null && !g.getOutput().getToolCalls().isEmpty())
				.findFirst()
				.orElse(generations.getLast());
		AssistantMessage output = chosen.getOutput();
		List<ToolCall> calls = output.getToolCalls().stream()
				.map(c -> new ToolCall(c.id(), c.name(), parse(c.arguments()), c.arguments()))
				.toList();
		String finish = chosen.getMetadata() == null ? null : chosen.getMetadata().getFinishReason();
		return new ModelReply(output.getText(), calls, usage(response), finish, output);
	}

	private Map<String, Object> parse(String arguments) {
		if (arguments == null || arguments.isBlank()) {
			return Map.of();
		}
		try {
			Map<String, Object> parsed = json.readValue(arguments, ARGUMENTS);
			return parsed == null ? Map.of() : parsed;
		}
		catch (JacksonException e) {
			// The loop then reports missing arguments back to the model, which can retry with valid JSON.
			return Map.of();
		}
	}

	private Usage usage(ChatResponse response) {
		if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
			return Usage.ZERO;
		}
		org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
		long input = orZero(usage.getPromptTokens());
		long output = orZero(usage.getCompletionTokens());
		long cacheRead = orZero(usage.getCacheReadInputTokens());
		long cacheWrite = orZero(usage.getCacheWriteInputTokens());
		return new Usage(input, output, cacheRead, cacheWrite, costMicroUsd(input, output, cacheRead, cacheWrite));
	}

	long costMicroUsd(long input, long output, long cacheRead, long cacheWrite) {
		if (pricing == null) {
			return 0;
		}
		BigDecimal usd = pricing.input().multiply(BigDecimal.valueOf(input))
				.add(pricing.output().multiply(BigDecimal.valueOf(output)))
				.add(pricing.cacheRead().multiply(BigDecimal.valueOf(cacheRead)))
				.add(pricing.cacheWrite().multiply(BigDecimal.valueOf(cacheWrite)))
				.divide(MILLION);
		return usd.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	private static long orZero(Number value) {
		return value == null ? 0 : value.longValue();
	}

	/** Spring AI resolves tool definitions from callbacks; ours are never invoked because the loop runs tools. */
	private record DefinitionOnlyTool(ToolDefinition definition) implements ToolCallback {

		DefinitionOnlyTool(ToolSpec spec) {
			this(ToolDefinition.builder().name(spec.name()).description(spec.description())
					.inputSchema(spec.inputSchema()).build());
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return definition;
		}

		@Override
		public String call(String toolInput) {
			throw new UnsupportedOperationException("tools are executed by AgentLoop, not by the chat model");
		}
	}
}
