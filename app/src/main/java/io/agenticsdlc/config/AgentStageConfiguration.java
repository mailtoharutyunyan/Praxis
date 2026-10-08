package io.agenticsdlc.config;

import io.agenticsdlc.core.agent.AgentLoop;
import io.agenticsdlc.core.agent.AgentModels;
import io.agenticsdlc.core.agent.tools.SandboxTools;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.engine.RunLimits;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.memory.MemoryRecall;
import io.agenticsdlc.core.memory.MemoryTools;
import io.agenticsdlc.core.memory.RepoMemory;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.stage.AgentStages;
import io.agenticsdlc.core.stage.RunWorkspace;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Model-driven stages (triage, spec, implement, review); each replaces its placeholder. They work in the run's
 * workspace, so they need the sandbox as well as {@code agentic.agent.enabled}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${agentic.sandbox.enabled:false} and ${agentic.agent.enabled:true}")
class AgentStageConfiguration {

	@Bean
	AgentStages agentStages(AgentModels models, RunWorkspace workspace, Sandbox sandbox, RepositoryCheckout checkout,
			RunLimits limits, AgenticProperties properties, RepoMemory memory, RunStore store, Clock clock) {
		AgenticProperties.Agent agent = properties.agent();
		AgentStages.Memory recall = !properties.memory().enabled() ? AgentStages.Memory.NONE
				: new AgentStages.Memory(new MemoryRecall(memory, sandbox, clock, properties.memory().retention()),
						new MemoryTools(memory, store, sandbox, clock).remember());
		return new AgentStages(models, workspace,
				new SandboxTools(sandbox, checkout, properties.sandbox().commandTimeout()), limits,
				new AgentLoop.Limits(agent.maxTurns(), agent.maxOutputTokens(), agent.maxToolResultChars(),
						agent.maxRepeats()), new AgentStages.Options(agent.testsFirst(), agent.specCritic()), recall);
	}

	@Bean
	StageHandler triageStage(AgentStages stages) {
		return stages.handler(RunState.TRIAGING);
	}

	@Bean
	StageHandler specifyStage(AgentStages stages) {
		return stages.handler(RunState.SPECIFYING);
	}

	@Bean
	StageHandler implementStage(AgentStages stages) {
		return stages.handler(RunState.IMPLEMENTING);
	}

	@Bean
	StageHandler reviewStage(AgentStages stages) {
		return stages.handler(RunState.REVIEWING);
	}
}
