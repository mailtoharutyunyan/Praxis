package io.agenticsdlc.core.memory;

import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.workspace.Sandbox;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Recalls what earlier runs learned about the run's repository. Each fact is used only if every cited line still
 * appears in the cited file of this working copy; a used fact is kept for another {@code retention}, unused ones
 * expire.
 */
public final class MemoryRecall {

	public static final Duration DEFAULT_RETENTION = Duration.ofDays(28);
	static final int MAX_FACTS = 20;

	private final RepoMemory memory;
	private final Sandbox sandbox;
	private final Clock clock;
	private final Duration retention;

	public MemoryRecall(RepoMemory memory, Sandbox sandbox, Clock clock, Duration retention) {
		this.memory = Objects.requireNonNull(memory, "memory");
		this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.retention = Objects.requireNonNull(retention, "retention");
	}

	/** The facts that still hold, as bullet lines; empty if none. */
	public Mono<List<String>> recall(StageContext context) {
		UUID runId = context.run().id();
		String repository = RepoFact.key(context.task().repository().cloneUrl());
		return memory.active(repository, clock.instant(), MAX_FACTS)
				.concatMap(fact -> holds(runId, fact).filter(Boolean::booleanValue)
						.flatMap(ok -> memory.used(fact.id(), clock.instant().plus(retention)).thenReturn(fact)))
				.map(fact -> "- " + fact.fact() + " (see " + fact.citations().getFirst().path() + ":"
						+ fact.citations().getFirst().line() + ")")
				.collectList()
				.onErrorResume(e -> Mono.just(List.of()));
	}

	/** Snippets are cut at {@link MemoryTools#MAX_SNIPPET_CHARS}; a cut one only needs to start the line. */
	static boolean matches(String line, String snippet) {
		return snippet.length() >= MemoryTools.MAX_SNIPPET_CHARS ? line.startsWith(snippet) : line.equals(snippet);
	}

	private Mono<Boolean> holds(UUID runId, RepoFact fact) {
		return Flux.fromIterable(fact.citations())
				.concatMap(citation -> sandbox.readFile(runId, citation.path(), 512 * 1024)
						.map(content -> content.lines().anyMatch(line -> matches(line.strip(), citation.snippet())))
						.onErrorResume(e -> Mono.just(false)))
				.all(Boolean::booleanValue);
	}
}
