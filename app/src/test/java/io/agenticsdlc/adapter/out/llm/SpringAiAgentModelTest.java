package io.agenticsdlc.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.core.agent.AgentMessage;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.ModelReply;
import io.agenticsdlc.core.agent.ModelRequest;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.agent.ToolResult;
import io.agenticsdlc.core.agent.ToolSpec;
import io.agenticsdlc.core.domain.Usage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

class SpringAiAgentModelTest {

	private static final AgenticProperties.Pricing OPUS = new AgenticProperties.Pricing(new BigDecimal("4.00"),
			new BigDecimal("20.00"), new BigDecimal("0.20"), new BigDecimal("5.00"));

	/** Records prompts and answers with a prepared response. */
	static final class FakeChatModel implements ChatModel {
		final List<Prompt> prompts = new ArrayList<>();
		ChatResponse next;

		@Override
		public ChatResponse call(Prompt prompt) {
			prompts.add(prompt);
			return next;
		}

		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().model("fake-model").maxTokens(1234).build();
		}
	}

	private final FakeChatModel chat = new FakeChatModel();
	private final SpringAiAgentModel model = new SpringAiAgentModel("fake/fake-model", chat, OPUS,
			JsonMapper.builder().build());

	private static ChatResponse response(List<Generation> generations, DefaultUsage usage) {
		return new ChatResponse(generations, ChatResponseMetadata.builder().usage(usage).build());
	}

	private static Generation generation(AssistantMessage message, String finish) {
		return new Generation(message, ChatGenerationMetadata.builder().finishReason(finish).build());
	}

	@Test
	void mapsConversationAndToolsKeepingModelDefaults() {
		AssistantMessage earlier = AssistantMessage.builder().content("looking")
				.toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "view_file", "{\"path\":\"a\"}")))
				.build();
		chat.next = response(List.of(generation(AssistantMessage.builder().content("done").build(), "end_turn")),
				new DefaultUsage(10, 5));

		model.complete(new ModelRequest("system prompt", List.of(
				new AgentMessage.User("task"),
				new AgentMessage.Assistant("looking", List.of(new ToolCall("t1", "view_file", Map.of("path", "a"),
						"{\"path\":\"a\"}")), earlier),
				new AgentMessage.ToolResults(List.of(new ToolResult("t1", "view_file", "content", false))),
				new AgentMessage.Assistant("rebuilt", List.of(new ToolCall("t2", "search", Map.of(), "{}")))),
				List.of(new ToolSpec("view_file", "View a file", "{\"type\":\"object\"}")), 4000)).block();

		Prompt prompt = chat.prompts.getFirst();
		List<Message> messages = prompt.getInstructions();
		assertThat(messages).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM, MessageType.USER,
				MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
		assertThat(messages.get(2)).isSameAs(earlier);
		assertThat(((AssistantMessage) messages.get(4)).getToolCalls()).singleElement()
				.satisfies(c -> assertThat(c.name()).isEqualTo("search"));
		assertThat(((ToolResponseMessage) messages.get(3)).getResponses()).singleElement()
				.satisfies(r -> assertThat(r.responseData()).isEqualTo("content"));

		ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
		assertThat(options.getModel()).isEqualTo("fake-model");
		assertThat(options.getMaxTokens()).isEqualTo(1234);
		assertThat(options.getToolCallbacks()).singleElement()
				.satisfies(t -> assertThat(t.getToolDefinition().name()).isEqualTo("view_file"));
		assertThatThrownBy(() -> options.getToolCallbacks().getFirst().call("{}"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void readsToolCallsFromTheRightGenerationWithUsageAndCost() {
		AssistantMessage thinkingOnly = AssistantMessage.builder().content("pondering").build();
		AssistantMessage answer = AssistantMessage.builder().content("I'll edit it")
				.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "edit_file",
						"{\"path\":\"src/A.java\",\"replace_all\":true}"),
						new AssistantMessage.ToolCall("c2", "function", "list_files", "not json")))
				.build();
		chat.next = response(List.of(generation(thinkingOnly, null), generation(answer, "tool_use")),
				new DefaultUsage(1_000, 500, 1_500, null, 2_000L, 100L));

		ModelReply reply = model.complete(new ModelRequest("s", List.of(new AgentMessage.User("go")), List.of(), 100))
				.block();

		assertThat(reply.text()).isEqualTo("I'll edit it");
		assertThat(reply.nativeMessage()).isSameAs(answer);
		assertThat(reply.stopReason()).isEqualTo("tool_use");
		assertThat(reply.toolCalls()).hasSize(2);
		assertThat(reply.toolCalls().getFirst().arguments()).containsEntry("path", "src/A.java")
				.containsEntry("replace_all", true);
		assertThat(reply.toolCalls().get(1).arguments()).isEmpty();
		assertThat(reply.toolCalls().get(1).rawArguments()).isEqualTo("not json");
		// 1000*4 + 500*20 + 2000*0.20 + 100*5 = 14,900 per million → $0.0149
		assertThat(reply.usage()).isEqualTo(new Usage(1_000, 500, 2_000, 100, 14_900));
	}

	@Test
	void unknownPricingCostsZeroAndEmptyResponsesFail() {
		SpringAiAgentModel unpriced = new SpringAiAgentModel("x", chat, null, JsonMapper.builder().build());
		assertThat(unpriced.costMicroUsd(1_000_000, 1_000_000, 0, 0)).isZero();
		assertThat(model.costMicroUsd(1_000_000, 0, 0, 0)).isEqualTo(4_000_000);

		chat.next = new ChatResponse(List.of());
		assertThatThrownBy(() -> model.complete(new ModelRequest("s", List.of(new AgentMessage.User("go")), List.of(),
				100)).block()).hasMessageContaining("no output");
	}

	@Test
	void transientFailuresAreRetriedOthersAreNot() {
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		ChatResponse ok = response(List.of(generation(AssistantMessage.builder().content("ok").build(), "end_turn")),
				new DefaultUsage(1, 1));
		ChatModel flaky = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				if (calls.incrementAndGet() < 3) {
					throw new org.springframework.ai.retry.TransientAiException("529 overloaded");
				}
				return ok;
			}

			@Override
			public ChatOptions getOptions() {
				return chat.getOptions();
			}
		};
		SpringAiAgentModel.CallPolicy fast = new SpringAiAgentModel.CallPolicy(java.time.Duration.ofSeconds(5), 3,
				java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5));
		SpringAiAgentModel retrying = new SpringAiAgentModel("x", flaky, OPUS, JsonMapper.builder().build(), fast);
		ModelRequest request = new ModelRequest("s", List.of(new AgentMessage.User("hi")), List.of(), 100);

		assertThat(retrying.complete(request).block().text()).isEqualTo("ok");
		assertThat(calls).hasValue(3);

		calls.set(-100);
		assertThatThrownBy(() -> retrying.complete(request).block())
				.isInstanceOf(org.springframework.ai.retry.TransientAiException.class);
		assertThat(calls).hasValue(-96);

		assertThat(SpringAiAgentModel.transientFailure(new IllegalStateException("x",
				new java.io.UncheckedIOException(new java.io.IOException("reset"))))).isTrue();
		assertThat(SpringAiAgentModel.transientFailure(new java.util.concurrent.TimeoutException())).isTrue();
		assertThat(SpringAiAgentModel.transientFailure(new org.springframework.ai.retry.NonTransientAiException("400")))
				.isFalse();
		assertThat(SpringAiAgentModel.transientFailure(new IllegalArgumentException("bad request"))).isFalse();
	}

	@Test
	void registryValidatesConfigurationLazily() {
		AgenticProperties.Models models = new AgenticProperties.Models(
				Map.of("anthropic", new AgenticProperties.Provider("anthropic", "test-key", "", "", "")),
				Map.of("coder", new AgenticProperties.RoleModel("anthropic", "claude-opus-5-5", 16000, "high"),
						"planner", new AgenticProperties.RoleModel("missing", "m", 1000, ""),
						"reviewer", new AgenticProperties.RoleModel("anthropic", "claude-opus-5-5", 16000, "")),
				Map.of("claude-opus-5-5", OPUS));
		SpringAiAgentModels registry = new SpringAiAgentModels(properties(models), JsonMapper.builder().build());

		assertThat(registry.forRole(AgentRole.CODER).id()).isEqualTo("anthropic/claude-opus-5-5");
		assertThat(registry.forRole(AgentRole.CODER)).isSameAs(registry.forRole(AgentRole.CODER));
		assertThatThrownBy(() -> registry.forRole(AgentRole.TRIAGE)).hasMessageContaining("no model configured");
		assertThatThrownBy(() -> registry.forRole(AgentRole.PLANNER)).hasMessageContaining("unknown provider");
	}

	@Test
	void factoryBuildsEveryProviderTypeOffline() {
		AgenticProperties.RoleModel role = new AgenticProperties.RoleModel("p", "some-model", 2000, "low");
		for (String type : List.of("anthropic", "openai", "azure-openai", "ollama", "google-genai")) {
			ChatModel built = ChatModelFactory.create(new AgenticProperties.Provider(type, "key",
					type.equals("azure-openai") ? "https://acme.openai.azure.com" : "", "", "dep"), role);
			assertThat(built).as(type).isNotNull();
		}
		assertThat(ChatModelFactory.create(new AgenticProperties.Provider("bedrock", "", "", "us-east-1", ""), role))
				.isNotNull();
		assertThatThrownBy(() -> ChatModelFactory.create(new AgenticProperties.Provider("bedrock", "", "", "", ""), role))
				.hasMessageContaining("region");
		assertThatThrownBy(() -> ChatModelFactory.create(new AgenticProperties.Provider("azure-openai", "k", "", "", ""),
				role)).hasMessageContaining("base-url");
		assertThatThrownBy(() -> ChatModelFactory.create(new AgenticProperties.Provider("watson", "", "", "", ""), role))
				.hasMessageContaining("unknown model provider");
	}

	private static AgenticProperties properties(AgenticProperties.Models models) {
		AgenticProperties.Agent agent = new AgenticProperties.Agent(true, 60, 12000, 3, 16000, java.time.Duration.ofMinutes(10),
				4, true, true);
		return new AgenticProperties(null, null, null, null, null, null, null, null, models, agent, null, null, null, null);
	}
}
