package io.agenticsdlc.adapter.out.llm;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.core.agent.AgentModel;
import io.agenticsdlc.core.agent.AgentModels;
import io.agenticsdlc.core.agent.AgentRole;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Role → model registry from {@code agentic.models.*}. Models are built on first use, so the application starts
 * without any API key and a missing configuration surfaces as a stage failure (escalated to a human) for the run
 * that needs it.
 */
@Component
class SpringAiAgentModels implements AgentModels {

	private static final Logger log = LoggerFactory.getLogger(SpringAiAgentModels.class);

	private final AgenticProperties.Models settings;
	private final SpringAiAgentModel.CallPolicy policy;
	private final JsonMapper json;
	private final Map<AgentRole, AgentModel> cache = new EnumMap<>(AgentRole.class);

	SpringAiAgentModels(AgenticProperties properties, JsonMapper json) {
		this.settings = properties.models();
		this.policy = new SpringAiAgentModel.CallPolicy(properties.agent().modelTimeout(), properties.agent().modelRetries(),
				SpringAiAgentModel.CallPolicy.DEFAULT.firstBackoff(), SpringAiAgentModel.CallPolicy.DEFAULT.maxBackoff());
		this.json = json;
	}

	@Override
	public synchronized AgentModel forRole(AgentRole role) {
		return cache.computeIfAbsent(role, this::build);
	}

	private AgentModel build(AgentRole role) {
		String key = role.name().toLowerCase(Locale.ROOT);
		AgenticProperties.RoleModel roleModel = settings.roles().get(key);
		if (roleModel == null) {
			throw new IllegalStateException("no model configured for role " + key + " (agentic.models.roles." + key + ")");
		}
		AgenticProperties.Provider provider = settings.providers().get(roleModel.provider());
		if (provider == null) {
			throw new IllegalStateException("role " + key + " uses unknown provider '" + roleModel.provider() + "'");
		}
		AgenticProperties.Pricing pricing = settings.pricing().get(roleModel.model());
		if (pricing == null) {
			log.warn("no pricing for model {}; its cost is tracked as zero (token budgets still apply)", roleModel.model());
		}
		String id = provider.type() + "/" + roleModel.model();
		log.info("role {} uses {}", key, id);
		return new SpringAiAgentModel(id, ChatModelFactory.create(provider, roleModel), pricing, json, policy);
	}
}
