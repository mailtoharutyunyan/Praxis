package io.agenticsdlc.core.scm;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static io.agenticsdlc.core.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PublishingTest {

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final List<String> pushes = new ArrayList<>();
	private final List<PullRequests.OpenRequest> opened = new ArrayList<>();
	private PullRequests.PullRequestState remoteState = PullRequests.PullRequestState.OPEN;

	private final ChangePublisher publisher = (view, message) -> {
		pushes.add(message);
		return Mono.just(new ChangePublisher.PushedBranch("agent/" + view.run().id(), "main", "c0ffee"));
	};
	private final PullRequests pullRequests = new PullRequests() {
		@Override
		public Mono<PullRequest> open(RunView view, OpenRequest request) {
			opened.add(request);
			return Mono.just(new PullRequest("42", "https://github.com/acme/shop/pull/42"));
		}

		@Override
		public Mono<PullRequestState> state(RunView view, PullRequest pullRequest) {
			assertThat(pullRequest.id()).isEqualTo("42");
			return Mono.just(remoteState);
		}
	};
	private UUID runId;

	@BeforeEach
	void setUp() {
		RunView view = new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES).submit(Fixtures.jira("jira"))
				.block().view();
		runId = view.run().id();
		Run run = store.run(runId);
		Run publishing = run.transitionTo(RunState.TRIAGING, T0)
				.triaged(RiskLevel.LOW, GatePolicy.forRisk(RiskLevel.LOW, Trust.UNTRUSTED), T0)
				.transitionTo(RunState.PREPARING_CONTEXT, T0).transitionTo(RunState.SPECIFYING, T0)
				.transitionTo(RunState.IMPLEMENTING, T0).transitionTo(RunState.VERIFYING, T0)
				.transitionTo(RunState.REVIEWING, T0).awaitApproval(Gate.PUBLISH, T0)
				.decide(Gate.PUBLISH, GateDecision.APPROVE, T0);
		store.update(run, publishing, List.of(
				RunEvent.of(runId, RunEventType.ARTIFACT_PRODUCED, "system", Map.of("kind", "spec", "content", "THE SPEC"), T0),
				RunEvent.of(runId, RunEventType.ARTIFACT_PRODUCED, "system", Map.of("kind", "review",
						"content", "VERDICT: APPROVE"), T0))).block();
	}

	private StageContext context() {
		store.claim("w", Duration.ofSeconds(30)).block();
		return new StageContext(store.find(runId).block(), store, "w", CLOCK);
	}

	@Test
	void pushesOpensPullRequestAndRecordsIt() {
		StageOutcome outcome = new PublishStage(publisher, pullRequests).execute(context()).block();

		assertThat(outcome).isInstanceOfSatisfying(StageOutcome.Completed.class,
				c -> assertThat(c.summary()).containsEntry("pullRequest", "https://github.com/acme/shop/pull/42"));
		assertThat(pushes).singleElement().satisfies(m -> assertThat(m).startsWith("SHOP-42: Add search")
				.contains("Agentic-SDLC-Run: " + runId));
		assertThat(opened).singleElement().satisfies(r -> {
			assertThat(r.branch()).isEqualTo("agent/" + runId);
			assertThat(r.baseBranch()).isEqualTo("main");
			assertThat(r.title()).isEqualTo("SHOP-42: Add search");
			assertThat(r.body()).contains("THE SPEC", "VERDICT: APPROVE", "approved by a human", "risk: LOW");
		});
		assertThat(store.allEvents(runId).getLast().payload()).containsEntry("kind", "pull-request")
				.containsEntry("id", "42").containsEntry("commit", "c0ffee");
	}

	@Test
	void trackerFinishesRunsWhenThePullRequestIsMergedOrClosed() {
		RunCommands commands = new RunCommands(store, CLOCK, false);
		PullRequestTracker tracker = new PullRequestTracker(store, pullRequests, commands);
		new PublishStage(publisher, pullRequests).execute(context()).block();
		Run published = store.run(runId);
		store.update(published, published.transitionTo(RunState.PR_OPEN, T0), List.of()).block();
		store.releaseLease(runId, "w").block();

		assertThat(tracker.sweep().collectList().block()).isEmpty();
		assertThat(store.run(runId).state()).isEqualTo(RunState.PR_OPEN);

		remoteState = PullRequests.PullRequestState.MERGED;
		assertThat(tracker.sweep().collectList().block()).containsExactly(runId);
		assertThat(store.run(runId).state()).isEqualTo(RunState.DONE);
		assertThat(store.allEvents(runId).stream().filter(e -> e.type() == RunEventType.STAGE_COMPLETED).toList().getLast()
				.payload()).containsEntry("outcome", "Merged");
	}

	@Test
	void closedWithoutMergeCancels() {
		RunCommands commands = new RunCommands(store, CLOCK, false);
		new PublishStage(publisher, pullRequests).execute(context()).block();
		Run published = store.run(runId);
		store.update(published, published.transitionTo(RunState.PR_OPEN, T0), List.of()).block();
		remoteState = PullRequests.PullRequestState.CLOSED;
		new PullRequestTracker(store, pullRequests, commands).sweep().blockLast();
		assertThat(store.run(runId).state()).isEqualTo(RunState.CANCELLED);
	}

	@Test
	void titlesAreBounded() {
		assertThat(PublishStage.title(new io.agenticsdlc.core.domain.Task(UUID.randomUUID(),
				io.agenticsdlc.core.domain.TaskOrigin.PROMPT, null, "x".repeat(400), "d", Fixtures.REPO, null,
				Trust.TRUSTED, "a", null, T0))).hasSize(250);
	}
}
