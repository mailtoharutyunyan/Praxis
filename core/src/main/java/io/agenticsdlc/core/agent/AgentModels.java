package io.agenticsdlc.core.agent;

/** Which model serves which role. Configured per deployment; roles may share a model. */
public interface AgentModels {

	AgentModel forRole(AgentRole role);
}
