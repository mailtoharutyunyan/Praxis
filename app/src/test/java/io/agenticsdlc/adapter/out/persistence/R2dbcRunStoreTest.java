package io.agenticsdlc.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.TestcontainersConfigurationAccess;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.port.ConcurrentRunUpdateException;
import io.agenticsdlc.core.port.LeaseLostException;
import io.agenticsdlc.core.port.RunStore;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Import(TestcontainersConfigurationAccess.class)
@SpringBootTest(properties = { "agentic.worker.enabled=false", "agentic.sandbox.enabled=false" })
class R2dbcRunStoreTest {

	private static final RepositoryRef REPO = new RepositoryRef(ScmKind.GITHUB,
			URI.create("https://github.com/acme/shop.git"));

	@Autowired
	RunStore store;

	@Autowired
	PostgresRunChangeSignals signals;

	private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

	private Task task(String key) {
		return new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "Add search", "Describe \u0000 it", REPO, null,
				Trust.TRUSTED, "alice-" + UUID.randomUUID(), key, now);
	}

	private RunStore.Submission submit(Task task) {
		Run run = Run.start(UUID.randomUUID(), task.id(), now);
		RunEvent created = RunEvent.of(run.id(), RunEventType.RUN_CREATED, task.requestedBy(),
				Map.of("title", task.title()), now);
		return store.submit(task, run, List.of(created)).block();
	}

	@Test
	void submitAndFindRoundTrip() {
		Task task = task(null);
		RunStore.Submission submission = submit(task);
		RunView found = store.find(submission.view().run().id()).block();

		assertThat(found.task().title()).isEqualTo("Add search");
		assertThat(found.task().description()).isEqualTo("Describe  it");
		assertThat(found.run()).isEqualTo(submission.view().run());
		assertThat(store.events(found.run().id(), 0, 10).collectList().block()).singleElement()
				.satisfies(e -> assertThat(e.seq()).isEqualTo(1));
	}

	@Test
	void idempotencyKeyReturnsExistingRun() {
		Task first = task("key-1");
		RunStore.Submission original = submit(first);
		Task retry = new Task(UUID.randomUUID(), first.origin(), null, first.title(), first.description(), REPO, null,
				first.trust(), first.requestedBy(), "key-1", now);
		RunStore.Submission again = submit(retry);

		assertThat(again.created()).isFalse();
		assertThat(again.view().run().id()).isEqualTo(original.view().run().id());
	}

	@Test
	void updateIsVersionFencedAndAppendsGapFreeEvents() {
		Run run = submit(task(null)).view().run();
		Run triaging = run.transitionTo(RunState.TRIAGING, now);
		Run stored = store.update(run, triaging, List.of(stateChanged(run, "RECEIVED", "TRIAGING"))).block();
		assertThat(stored.version()).isEqualTo(1);

		StepVerifier.create(store.update(run, triaging, List.of()))
				.expectError(ConcurrentRunUpdateException.class).verify();

		Run triaged = stored.triaged(RiskLevel.HIGH, GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED), now)
				.addUsage(new Usage(10, 20, 30, 40, 50), now);
		Run saved = store.update(stored, triaged, List.of(event(run, RunEventType.TRIAGED, Map.of("risk", "HIGH")),
				event(run, RunEventType.USAGE_RECORDED, Map.of("costMicroUsd", 50)))).block();

		RunView reloaded = store.find(run.id()).block();
		assertThat(reloaded.run()).isEqualTo(saved);
		assertThat(reloaded.run().gatePolicy().gates()).containsExactly(Gate.SPEC, Gate.IMPLEMENTATION, Gate.PUBLISH);
		assertThat(store.events(run.id(), 0, 100).map(RunEvent::seq).collectList().block())
				.containsExactly(1L, 2L, 3L, 4L);
		assertThat(store.events(run.id(), 2, 100).map(RunEvent::type).collectList().block())
				.containsExactly(RunEventType.TRIAGED, RunEventType.USAGE_RECORDED);
	}

	@Test
	void payloadRoundTripsJsonValues() {
		Run run = submit(task(null)).view().run();
		Map<String, Object> payload = new HashMap<>();
		payload.put("text", "a\u0000b");
		payload.put("number", 42);
		payload.put("flag", true);
		payload.put("none", null);
		payload.put("list", List.of("x", 1));
		payload.put("nested", Map.of("k", "v"));
		store.update(run, run, List.of(event(run, RunEventType.COMMAND_OUTPUT, payload))).block();

		RunEvent stored = store.events(run.id(), 1, 10).blockFirst();
		assertThat(stored.payload()).containsEntry("text", "ab").containsEntry("number", 42)
				.containsEntry("flag", true).containsEntry("none", null).containsEntry("list", List.of("x", 1))
				.containsEntry("nested", Map.of("k", "v"));
	}

	@Test
	void claimLeasesOneRunToOneOwnerAndFencesStaleWriters() {
		Run run = submit(task(null)).view().run();

		List<Run> claims = Flux.range(0, 8)
				.flatMap(i -> store.claim("worker-" + i, Duration.ofSeconds(30)))
				.filter(r -> r.id().equals(run.id()))
				.collectList().block();
		assertThat(claims).hasSize(1);
		Run claimed = claims.getFirst();
		assertThat(claimed.version()).isEqualTo(run.version() + 1);

		StepVerifier.create(store.update(run, run.transitionTo(RunState.TRIAGING, now), List.of()))
				.expectError(ConcurrentRunUpdateException.class).verify();
	}

	@Test
	void leaseRenewAppendAndRelease() {
		Run run = submit(task(null)).view().run();
		String owner = "lease-test-" + UUID.randomUUID();
		claimSpecific(run.id(), owner);

		assertThat(store.renewLease(run.id(), owner, Duration.ofSeconds(30)).block()).isTrue();
		assertThat(store.renewLease(run.id(), "someone-else", Duration.ofSeconds(30)).block()).isFalse();

		store.append(run.id(), owner, List.of(event(run, RunEventType.AGENT_MESSAGE, Map.of("m", "hi")))).block();
		StepVerifier.create(store.append(run.id(), "someone-else",
				List.of(event(run, RunEventType.AGENT_MESSAGE, Map.of("m", "no")))))
				.expectError(LeaseLostException.class).verify();

		store.releaseLease(run.id(), owner).block();
		assertThat(store.renewLease(run.id(), owner, Duration.ofSeconds(30)).block()).isFalse();
		assertThat(store.events(run.id(), 0, 10).count().block()).isEqualTo(2);
	}

	@Test
	void listFiltersByStateNewestFirstWithCursor() {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Task task = new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "t" + i, "d", REPO, null, Trust.TRUSTED,
					"lister", null, now.plusSeconds(i));
			Run run = Run.start(UUID.randomUUID(), task.id(), now.plusSeconds(1000 + i));
			store.submit(task, run, List.of()).block();
			ids.add(run.id());
		}
		List<RunView> page = store.list(Set.of(RunState.RECEIVED), now.plusSeconds(1002), 2).collectList().block();
		assertThat(page).extracting(v -> v.run().id()).containsExactly(ids.get(1), ids.get(0));
		assertThat(store.list(Set.of(RunState.DONE), null, 10).collectList().block())
				.noneMatch(v -> ids.contains(v.run().id()));
	}

	@Test
	void commitsNotifyListeners() {
		Run run = submit(task(null)).view().run();
		StepVerifier.create(signals.changes().filter(run.id()::equals).next())
				.then(() -> store.update(run, run, List.of(event(run, RunEventType.AGENT_MESSAGE, Map.of()))).block())
				.expectNext(run.id())
				.expectComplete()
				.verify(Duration.ofSeconds(10));
	}

	/** Claims until the given run is leased by {@code owner}; other runs claimed on the way are released. */
	private void claimSpecific(UUID runId, String owner) {
		for (int i = 0; i < 100; i++) {
			Run claimed = store.claim(owner, Duration.ofSeconds(30)).block();
			if (claimed == null) {
				break;
			}
			if (claimed.id().equals(runId)) {
				return;
			}
		}
		throw new AssertionError("could not claim " + runId);
	}

	private RunEvent stateChanged(Run run, String from, String to) {
		return event(run, RunEventType.STATE_CHANGED, Map.of("from", from, "to", to));
	}

	private RunEvent event(Run run, RunEventType type, Map<String, Object> payload) {
		return RunEvent.of(run.id(), type, "system", payload, now);
	}
}
