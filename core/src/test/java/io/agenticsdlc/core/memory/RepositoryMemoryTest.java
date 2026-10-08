package io.agenticsdlc.core.memory;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.agent.AgentTool;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.agent.ToolException;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import io.agenticsdlc.core.support.MemorySandbox;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RepositoryMemoryTest {

	/** Just enough of the store for the tool and recall. */
	static final class Facts implements RepoMemory {
		final List<RepoFact> facts = new ArrayList<>();
		final List<UUID> used = new ArrayList<>();

		@Override
		public Mono<Void> propose(RepoFact fact) {
			facts.add(fact);
			return Mono.empty();
		}

		@Override
		public Mono<Long> countFromRun(UUID runId) {
			return Mono.just(facts.stream().filter(f -> runId.equals(f.sourceRunId())).count());
		}

		@Override
		public Flux<RepoFact> active(String repository, Instant now, int limit) {
			return Flux.fromIterable(facts).filter(f -> f.repository().equals(repository)
					&& f.status() == RepoFact.Status.ACTIVE && f.expiresAt().isAfter(now));
		}

		@Override
		public Flux<RepoFact> list(String repository, int limit) {
			return Flux.fromIterable(facts);
		}

		@Override
		public Mono<Long> settleRun(UUID runId, boolean merged, Instant expiresAt) {
			facts.replaceAll(f -> runId.equals(f.sourceRunId()) ? new RepoFact(f.id(), f.repository(), f.fact(),
					f.citations(), merged ? RepoFact.Status.ACTIVE : RepoFact.Status.DISABLED, runId, f.createdAt(),
					expiresAt) : f);
			return Mono.just(1L);
		}

		@Override
		public Mono<Void> used(UUID id, Instant expiresAt) {
			used.add(id);
			return Mono.empty();
		}

		@Override
		public Mono<RepoFact> setStatus(UUID id, RepoFact.Status status, Instant expiresAt) {
			return Mono.empty();
		}
	}

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final MemorySandbox sandbox = new MemorySandbox();
	private final Facts memory = new Facts();
	private final AgentTool remember = new MemoryTools(memory, store, sandbox, CLOCK).remember();
	private final MemoryRecall recall = new MemoryRecall(memory, sandbox, CLOCK, Duration.ofDays(28));
	private StageContext context;

	@BeforeEach
	void setUp() {
		RunView view = new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES).submit(Fixtures.prompt("alice"))
				.block().view();
		store.claim("w", Duration.ofSeconds(30)).block();
		context = new StageContext(view, store, "w", CLOCK);
		sandbox.files.put("pom.xml", "<project>\n  <profile><id>it</id></profile>\n</project>\n");
	}

	private String remember(Map<String, Object> args) {
		return remember.execute(context.run().id(), new ToolCall("1", "remember", args, args.toString())).block();
	}

	@Test
	void factsAreStoredWithCheckedCitationsAndRecalledOnlyOnceActiveAndStillTrue() {
		assertThat(remember(Map.of("fact", "Integration tests need the Maven profile 'it'.",
				"citations", List.of(Map.of("path", "pom.xml", "line", 2))))).contains("once a human merges");
		RepoFact stored = memory.facts.getFirst();
		assertThat(stored.status()).isEqualTo(RepoFact.Status.CANDIDATE);
		assertThat(stored.repository()).isEqualTo(RepoFact.key(context.task().repository().cloneUrl()));
		assertThat(stored.citations().getFirst().snippet()).isEqualTo("<profile><id>it</id></profile>");

		assertThat(recall.recall(context).block()).as("candidates are not recalled").isEmpty();
		memory.settleRun(context.run().id(), true, CLOCK.instant().plus(Duration.ofDays(28))).block();
		assertThat(recall.recall(context).block()).singleElement().asString()
				.contains("profile 'it'", "(see pom.xml:2)");
		assertThat(memory.used).containsExactly(stored.id());

		// The code moved on: the cited line is gone, so the fact is not used.
		sandbox.files.put("pom.xml", "<project/>\n");
		assertThat(recall.recall(context).block()).isEmpty();
	}

	@Test
	void citationsMustPointAtRealContentAndRunsCannotFloodMemory() {
		assertThatThrownBy(() -> remember(Map.of("fact", "x", "citations", List.of())))
				.isInstanceOf(ToolException.class).hasMessageContaining("at least one");
		assertThatThrownBy(() -> remember(Map.of("fact", "x", "citations", List.of(Map.of("path", "pom.xml", "line", 9)))))
				.hasMessageContaining("no line 9");
		sandbox.files.put("App.java", "class App {\n  }\n}\n");
		assertThatThrownBy(() -> remember(Map.of("fact", "x", "citations", List.of(Map.of("path", "App.java", "line", 2)))))
				.as("too little content to cite").hasMessageContaining("enough content");
		assertThatThrownBy(() -> remember(Map.of("fact", "x".repeat(301), "citations",
				List.of(Map.of("path", "pom.xml", "line", 2))))).hasMessageContaining("at most 300");
		for (int i = 0; i < MemoryTools.MAX_FACTS_PER_RUN; i++) {
			remember(Map.of("fact", "fact " + i, "citations", List.of(Map.of("path", "pom.xml", "line", 2))));
		}
		assertThatThrownBy(() -> remember(Map.of("fact", "one more", "citations",
				List.of(Map.of("path", "pom.xml", "line", 2))))).hasMessageContaining("already saved 10");
	}

	@Test
	void repositoryKeysIgnoreSpelling() {
		assertThat(RepoFact.key(URI.create("https://GitHub.com/Acme/Shop.git/"))).isEqualTo("github.com/acme/shop");
		assertThat(RepoFact.key(URI.create("https://github.com/acme/shop"))).isEqualTo("github.com/acme/shop");
		assertThat(MemoryRecall.matches("x".repeat(250), "x".repeat(200))).isTrue();
		assertThat(MemoryRecall.matches("abc def", "abc")).isFalse();
	}
}
