package io.agenticsdlc.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.core.agent.AgentMessage;
import io.agenticsdlc.core.agent.ModelReply;
import io.agenticsdlc.core.agent.ModelRequest;
import io.agenticsdlc.core.agent.ToolResult;
import io.agenticsdlc.core.agent.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real API round trip: a tool call, then the tool result fed back with the provider's own assistant message
 * (signed thinking blocks included). Runs only when ANTHROPIC_API_KEY is set; costs a fraction of a cent.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LiveAnthropicSmokeTest {

	@Test
	void toolCallRoundTripWithClaude() {
		AgenticProperties.Provider provider = new AgenticProperties.Provider("anthropic", System.getenv("ANTHROPIC_API_KEY"),
				"", "", "");
		AgenticProperties.RoleModel role = new AgenticProperties.RoleModel("anthropic", "claude-opus-5-5", 2000, "low");
		SpringAiAgentModel model = new SpringAiAgentModel("anthropic/claude-opus-5-5",
				ChatModelFactory.create(provider, role), null, JsonMapper.builder().build());
		ToolSpec lookup = new ToolSpec("lookup_version", "Returns the project's version string.",
				"{\"type\":\"object\",\"properties\":{}}");

		AgentMessage.User ask = new AgentMessage.User("What is the project's version? Use the tool, then answer with just it.");
		ModelReply first = model.complete(new ModelRequest("Answer briefly.", List.of(ask), List.of(lookup), 2000)).block();
		assertThat(first.toolCalls()).as("first reply: %s", first.text()).isNotEmpty();
		assertThat(first.usage().inputTokens()).isPositive();

		ModelReply second = model.complete(new ModelRequest("Answer briefly.", List.of(ask,
				new AgentMessage.Assistant(first.text(), first.toolCalls(), first.nativeMessage()),
				new AgentMessage.ToolResults(List.of(new ToolResult(first.toolCalls().getFirst().id(), "lookup_version",
						"7.4.2", false)))),
				List.of(lookup), 2000)).block();
		assertThat(second.toolCalls()).isEmpty();
		assertThat(second.text()).contains("7.4.2");
	}
}
