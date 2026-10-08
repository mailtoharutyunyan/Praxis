package io.agenticsdlc.core.memory;

import io.agenticsdlc.core.agent.AgentTool;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.agent.ToolException;
import io.agenticsdlc.core.agent.ToolSpec;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.WorkspacePath;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The {@code remember} tool: agents store durable facts about the repository, each citing the lines that show it.
 * Citations are read from the working copy when stored; a run may store a handful of facts, and they stay candidates
 * until a human merges the run's pull request.
 */
public final class MemoryTools {

	public static final int MAX_FACTS_PER_RUN = 10;
	static final int MAX_CITATIONS = 5;
	static final int MAX_SNIPPET_CHARS = 200;
	private static final int MIN_SNIPPET_CHARS = 8;

	private final RepoMemory memory;
	private final RunStore store;
	private final Sandbox sandbox;
	private final Clock clock;

	public MemoryTools(RepoMemory memory, RunStore store, Sandbox sandbox, Clock clock) {
		this.memory = Objects.requireNonNull(memory, "memory");
		this.store = Objects.requireNonNull(store, "store");
		this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public AgentTool remember() {
		ToolSpec spec = new ToolSpec("remember", """
				Save one durable fact about this repository for future changes: a build or test quirk, a \
				convention, where something lives. Not details of the current task. Cite the line(s) of code that \
				show it; facts are checked against those lines before they are used again.""", """
				{"type":"object","required":["fact","citations"],"properties":{
				"fact":{"type":"string","description":"One sentence, at most 300 characters."},
				"citations":{"type":"array","minItems":1,"maxItems":5,"items":{"type":"object","required":["path","line"],
				"properties":{"path":{"type":"string"},"line":{"type":"integer","description":"1-based."}}}}}}""");
		return new AgentTool() {
			@Override
			public ToolSpec spec() {
				return spec;
			}

			@Override
			public Mono<String> execute(UUID runId, ToolCall call) {
				return Mono.defer(() -> store(runId, call));
			}

			@Override
			public boolean mutates() {
				return false;
			}
		};
	}

	private Mono<String> store(UUID runId, ToolCall call) {
		String fact = call.requiredString("fact").strip();
		if (fact.isEmpty() || fact.length() > RepoFact.MAX_FACT_CHARS) {
			throw new ToolException("fact must be one sentence of at most " + RepoFact.MAX_FACT_CHARS + " characters");
		}
		List<Map<?, ?>> cited = citations(call);
		return memory.countFromRun(runId).flatMap(count -> {
			if (count >= MAX_FACTS_PER_RUN) {
				throw new ToolException("this run already saved " + count + " facts; save only the most useful ones");
			}
			return Flux.fromIterable(cited).concatMap(c -> citation(runId, c)).collectList();
		}).flatMap(citations -> store.find(runId).flatMap(view -> memory.propose(new RepoFact(UUID.randomUUID(),
				RepoFact.key(view.task().repository().cloneUrl()), fact, citations, RepoFact.Status.CANDIDATE, runId,
				clock.instant(), null))))
				.thenReturn("Saved. It will be used in later runs once a human merges this run's pull request.");
	}

	private static List<Map<?, ?>> citations(ToolCall call) {
		if (!(call.arguments().get("citations") instanceof List<?> list) || list.isEmpty()) {
			throw new ToolException("citations must list at least one {path, line} that shows the fact");
		}
		List<Map<?, ?>> cited = new ArrayList<>();
		for (Object item : list.subList(0, Math.min(list.size(), MAX_CITATIONS))) {
			if (!(item instanceof Map<?, ?> map)) {
				throw new ToolException("each citation is an object {path, line}");
			}
			cited.add(map);
		}
		return cited;
	}

	private Mono<RepoFact.Citation> citation(UUID runId, Map<?, ?> cited) {
		String path;
		try {
			path = WorkspacePath.relative(String.valueOf(cited.get("path")));
		}
		catch (IllegalArgumentException e) {
			throw new ToolException(e.getMessage());
		}
		int line;
		try {
			line = Integer.parseInt(String.valueOf(cited.get("line")).replaceAll("\\.0$", ""));
		}
		catch (NumberFormatException e) {
			throw new ToolException("citation line must be a number");
		}
		return sandbox.readFile(runId, path, 512 * 1024)
				.onErrorMap(e -> new ToolException("cannot read " + path + " to check the citation"))
				.map(content -> {
					List<String> lines = content.lines().toList();
					if (line < 1 || line > lines.size()
							|| lines.get(line - 1).replaceAll("\\s", "").length() < MIN_SNIPPET_CHARS) {
						throw new ToolException(path + " has no line " + line + " with enough content to cite; cite "
								+ "the line that actually shows the fact");
					}
					String snippet = lines.get(line - 1).strip();
					return new RepoFact.Citation(path, line, snippet.length() <= MAX_SNIPPET_CHARS ? snippet
							: snippet.substring(0, MAX_SNIPPET_CHARS));
				});
	}
}
