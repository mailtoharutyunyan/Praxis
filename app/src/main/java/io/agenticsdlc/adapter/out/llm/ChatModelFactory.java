package io.agenticsdlc.adapter.out.llm;

import com.anthropic.models.messages.OutputConfig;
import io.agenticsdlc.config.AgenticProperties;
import java.util.Locale;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Builds Spring AI chat models programmatically from {@code agentic.models.*} (no Spring AI autoconfiguration), so
 * several providers and models can serve different roles at once. Builder APIs verified against Spring AI 2.0.1.
 */
final class ChatModelFactory {

	private ChatModelFactory() {
	}

	static ChatModel create(AgenticProperties.Provider provider, AgenticProperties.RoleModel role) {
		return switch (provider.type().toLowerCase(Locale.ROOT)) {
			case "anthropic" -> anthropic(provider, role);
			case "openai" -> openAi(provider, role, false);
			case "azure-openai" -> openAi(provider, role, true);
			case "ollama" -> ollama(provider, role);
			case "bedrock" -> bedrock(provider, role);
			case "google-genai" -> googleGenAi(provider, role);
			default -> throw new IllegalArgumentException("unknown model provider type '" + provider.type()
					+ "'; supported: anthropic, openai, azure-openai, ollama, bedrock, google-genai");
		};
	}

	private static ChatModel anthropic(AgenticProperties.Provider provider, AgenticProperties.RoleModel role) {
		AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
				.model(role.model())
				.maxTokens(role.maxOutputTokens())
				// Agent loops resend a growing history every turn; caching it is the largest cost saving.
				.cacheOptions(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
						.cacheToolResults(true).build());
		if (!provider.apiKey().isBlank()) {
			options.apiKey(provider.apiKey());
		}
		if (!provider.baseUrl().isBlank()) {
			options.baseUrl(provider.baseUrl());
		}
		if (!role.effort().isBlank()) {
			options.effort(OutputConfig.Effort.of(role.effort().toLowerCase(Locale.ROOT)));
		}
		return AnthropicChatModel.builder().options(options.build()).build();
	}

	private static ChatModel openAi(AgenticProperties.Provider provider, AgenticProperties.RoleModel role,
			boolean azure) {
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
				.model(role.model())
				.maxCompletionTokens(role.maxOutputTokens());
		if (!provider.apiKey().isBlank()) {
			options.apiKey(provider.apiKey());
		}
		if (!provider.baseUrl().isBlank()) {
			options.baseUrl(provider.baseUrl());
		}
		if (azure) {
			if (provider.baseUrl().isBlank()) {
				throw new IllegalArgumentException("azure-openai provider needs base-url (the Azure OpenAI endpoint)");
			}
			options.azure(true);
			if (!provider.deployment().isBlank()) {
				options.deploymentName(provider.deployment());
			}
		}
		if (!role.effort().isBlank()) {
			options.reasoningEffort(role.effort().toLowerCase(Locale.ROOT));
		}
		return OpenAiChatModel.builder().options(options.build()).build();
	}

	private static ChatModel ollama(AgenticProperties.Provider provider, AgenticProperties.RoleModel role) {
		OllamaApi.Builder api = OllamaApi.builder();
		if (!provider.baseUrl().isBlank()) {
			api.baseUrl(provider.baseUrl());
		}
		return OllamaChatModel.builder()
				.ollamaApi(api.build())
				.options(OllamaChatOptions.builder().model(role.model()).numPredict(role.maxOutputTokens()).build())
				.build();
	}

	private static ChatModel bedrock(AgenticProperties.Provider provider, AgenticProperties.RoleModel role) {
		if (provider.region().isBlank()) {
			throw new IllegalArgumentException("bedrock provider needs a region");
		}
		return BedrockProxyChatModel.builder()
				.region(Region.of(provider.region()))
				.credentialsProvider(DefaultCredentialsProvider.builder().build())
				.options(BedrockChatOptions.builder().model(role.model()).maxTokens(role.maxOutputTokens()).build())
				.build();
	}

	private static ChatModel googleGenAi(AgenticProperties.Provider provider, AgenticProperties.RoleModel role) {
		com.google.genai.Client.Builder client = com.google.genai.Client.builder();
		if (!provider.apiKey().isBlank()) {
			client.apiKey(provider.apiKey());
		}
		return GoogleGenAiChatModel.builder()
				.genAiClient(client.build())
				.options(GoogleGenAiChatOptions.builder().model(role.model()).maxOutputTokens(role.maxOutputTokens())
						.build())
				.build();
	}
}
