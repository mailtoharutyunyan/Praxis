package io.agenticsdlc.core.agent;

import io.agenticsdlc.core.domain.Usage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Replies from a script, recording every request it received. */
public final class ScriptedModel implements AgentModel {

	public final List<ModelRequest> requests = new ArrayList<>();
	private final Deque<ModelReply> replies = new ArrayDeque<>();
	private ModelReply fallback;

	public ScriptedModel then(ModelReply reply) {
		replies.add(reply);
		return this;
	}

	public ScriptedModel thenCall(String tool, Map<String, Object> args) {
		return then(new ModelReply("", List.of(new ToolCall("call-" + (replies.size() + 1), tool, args, args.toString())),
				new Usage(100, 10, 0, 0, 10), "tool_use"));
	}

	public ScriptedModel thenAnswer(String text) {
		return then(new ModelReply(text, List.of(), new Usage(50, 20, 0, 0, 5), "end_turn"));
	}

	/** Reply used when the script is exhausted. */
	public ScriptedModel always(ModelReply reply) {
		fallback = reply;
		return this;
	}

	@Override
	public String id() {
		return "test/scripted";
	}

	@Override
	public Mono<ModelReply> complete(ModelRequest request) {
		requests.add(request);
		ModelReply next = replies.isEmpty() ? fallback : replies.poll();
		return next == null ? Mono.error(new IllegalStateException("script exhausted")) : Mono.just(next);
	}
}
