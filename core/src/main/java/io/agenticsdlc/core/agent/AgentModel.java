package io.agenticsdlc.core.agent;

import reactor.core.publisher.Mono;

/**
 * One model turn: given the conversation and the available tools, return text and/or tool calls. The model never
 * executes tools itself; {@link AgentLoop} does, so every call is visible, limited and auditable (ADR-0003).
 */
public interface AgentModel {

	/** Provider and model id, for events and metrics, e.g. {@code anthropic/claude-sonnet-5-5}. */
	String id();

	Mono<ModelReply> complete(ModelRequest request);
}
