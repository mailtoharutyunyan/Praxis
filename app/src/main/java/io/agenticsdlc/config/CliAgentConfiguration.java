package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.cli.ClaudeCodeAgent;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.agent.ExternalAgent;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agents on an AI CLI, Claude Code (ADR-0008). Whether they do is read per task: from the AI model connector when one
 * is saved in the UI, else from {@code agentic.agent.cli}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CliAgentProperties.class)
class CliAgentConfiguration {

	@Bean
	@ConditionalOnExpression("${agentic.sandbox.enabled:false} and ${agentic.agent.enabled:true}")
	ExternalAgent claudeCodeAgent(Sandbox sandbox, RepositoryCheckout checkout, CliAgentProperties cli,
			AgenticProperties properties, ConnectorSettings connectors, JsonMapper json) {
		return new ClaudeCodeAgent(sandbox, checkout, cli, () -> setup(connectors, cli, properties),
				properties.sandbox().network(), properties.limits().maxCostMicroUsd(), json);
	}

	/** The connector's choice when it is saved (its token, else the property's); otherwise the properties. */
	static Optional<ClaudeCodeAgent.Setup> setup(ConnectorSettings connectors, CliAgentProperties cli,
			AgenticProperties properties) {
		if (connectors.model().isPresent()) {
			return connectors.cliEngine().map(c -> new ClaudeCodeAgent.Setup(c.token().isBlank() ? cli.token() : c.token(),
					c.model(), c.roleModels(), c.apiFallback()));
		}
		if (!cli.enabled()) {
			return Optional.empty();
		}
		AgenticProperties.RoleModel coder = properties.models().roles().get("coder");
		AgenticProperties.Provider provider = coder == null ? null : properties.models().providers().get(coder.provider());
		boolean api = provider != null && (!provider.apiKey().isBlank()
				|| List.of("ollama", "bedrock").contains(provider.type()));
		return Optional.of(new ClaudeCodeAgent.Setup(cli.token(), cli.model(), Map.of(), api));
	}
}
