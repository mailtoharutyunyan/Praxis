package io.agenticsdlc.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.TestcontainersConfigurationAccess;
import io.agenticsdlc.core.memory.RepoFact;
import io.agenticsdlc.core.memory.RepoMemory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfigurationAccess.class)
@SpringBootTest(properties = { "agentic.worker.enabled=false", "agentic.sandbox.enabled=false" })
class R2dbcRepoMemoryTest {

	@Autowired
	RepoMemory memory;

	private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

	private RepoFact candidate(String repository, String text) {
		return new RepoFact(UUID.randomUUID(), repository, text, List.of(new RepoFact.Citation("pom.xml", 12,
				"<id>it</id>")), RepoFact.Status.CANDIDATE, null, now, null);
	}

	@Test
	void factsMoveFromCandidateToActiveAndExpire() {
		String repository = "github.com/acme/" + UUID.randomUUID();
		RepoFact fact = candidate(repository, "Integration tests need -Pit.");
		memory.propose(fact).block();
		assertThat(memory.active(repository, now, 10).collectList().block()).isEmpty();

		RepoFact active = memory.setStatus(fact.id(), RepoFact.Status.ACTIVE, now.plus(Duration.ofDays(28))).block();
		assertThat(active.status()).isEqualTo(RepoFact.Status.ACTIVE);
		assertThat(active.citations()).containsExactly(new RepoFact.Citation("pom.xml", 12, "<id>it</id>"));
		assertThat(memory.active(repository, now, 10).collectList().block()).extracting(RepoFact::id).containsExactly(fact.id());
		assertThat(memory.active(repository, now.plus(Duration.ofDays(29)), 10).collectList().block()).isEmpty();

		memory.used(fact.id(), now.plus(Duration.ofDays(60))).block();
		assertThat(memory.active(repository, now.plus(Duration.ofDays(29)), 10).collectList().block()).hasSize(1);

		memory.setStatus(fact.id(), RepoFact.Status.DISABLED, now).block();
		assertThat(memory.active(repository, now, 10).collectList().block()).isEmpty();
		assertThat(memory.list(repository, 10).collectList().block()).extracting(RepoFact::status)
				.containsExactly(RepoFact.Status.DISABLED);
		assertThat(memory.setStatus(UUID.randomUUID(), RepoFact.Status.ACTIVE, now).block()).isNull();
	}
}
