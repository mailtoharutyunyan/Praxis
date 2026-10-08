package io.agenticsdlc.adapter.out.llm;

import io.agenticsdlc.config.AgenticProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Checks that a model provider configuration answers, with one tiny request (a few tokens). */
public final class ModelProbe {

	private ModelProbe() {
	}

	/** Emits the model's reply, or errors with the provider's message. */
	public static Mono<String> probe(AgenticProperties.Provider provider, String model) {
		return Mono.fromCallable(() -> {
			ChatModel chat = ChatModelFactory.create(provider, new AgenticProperties.RoleModel(provider.type(), model, 256, ""));
			ChatResponse response = chat.call(new Prompt("Reply with the single word OK."));
			String text = response.getResult() == null || response.getResult().getOutput() == null ? ""
					: response.getResult().getOutput().getText();
			return text == null ? "" : text.strip();
		}).subscribeOn(Schedulers.boundedElastic()).timeout(java.time.Duration.ofSeconds(60));
	}
}
