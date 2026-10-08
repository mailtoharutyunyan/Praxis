package io.agenticsdlc.core.application;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static io.agenticsdlc.core.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.stage.RunHistory;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RevisionTest {

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final RunCommands commands = new RunCommands(store, CLOCK, false, 2);
	private UUID runId;

	@BeforeEach
	void prOpen() {
		runId = new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES).submit(Fixtures.prompt("alice"))
				.block().view().run().id();
		Run run = store.run(runId);
		Run open = run.transitionTo(RunState.TRIAGING, T0)
				.triaged(RiskLevel.LOW, GatePolicy.forRisk(RiskLevel.LOW, Trust.TRUSTED), T0)
				.transitionTo(RunState.PREPARING_CONTEXT, T0).transitionTo(RunState.SPECIFYING, T0)
				.transitionTo(RunState.IMPLEMENTING, T0).withFixIteration(T0).transitionTo(RunState.VERIFYING, T0)
				.transitionTo(RunState.REVIEWING, T0).awaitApproval(Gate.PUBLISH, T0)
				.decide(Gate.PUBLISH, GateDecision.APPROVE, T0).transitionTo(RunState.PR_OPEN, T0);
		store.update(run, open, List.of(RunEvent.of(runId, RunEventType.ARTIFACT_PRODUCED, "system",
				Map.of("kind", "pull-request", "url", "https://github.com/acme/shop/pull/7", "content", "u"), T0))).block();
	}

	private static RevisionRequest comment(String id, String text) {
		return new RevisionRequest("github", "github:comment:" + id, "bob", text, "src/App.java:12",
				"https://github.com/acme/shop/pull/7#issuecomment-" + id);
	}

	@Test
	void aRevisionSendsTheOpenPullRequestBackToImplementationForANewRound() {
		Run revised = commands.requestRevision(runId, comment("1", "Use a constant for the page size"), "github:bob")
				.block();

		assertThat(revised.state()).isEqualTo(RunState.IMPLEMENTING);
		assertThat(revised.fixIterations()).isZero();
		RunHistory history = new RunHistory(store.allEvents(runId));
		assertThat(history.currentRevision()).hasValueSatisfying(r -> {
			assertThat(r.describe()).contains("github by bob on src/App.java:12", "Use a constant");
		});
	}

	@Test
	void repeatedDeliveriesAreIgnoredAndRoundsAreCapped() {
		commands.requestRevision(runId, comment("1", "first"), "github:bob").block();
		assertThat(commands.requestRevision(runId, comment("1", "first"), "github:bob").block().state())
				.isEqualTo(RunState.IMPLEMENTING);
		assertThat(store.allEvents(runId).stream().filter(e -> e.type() == RunEventType.REVISION_REQUESTED)).hasSize(1);

		backToPrOpen();
		commands.requestRevision(runId, comment("2", "second"), "github:bob").block();
		backToPrOpen();
		assertThatThrownBy(() -> commands.requestRevision(runId, comment("3", "third"), "github:bob").block())
				.hasMessageContaining("limit of 2 revisions");
	}

	@Test
	void onlyAnOpenPullRequestCanBeRevised() {
		commands.requestRevision(runId, comment("1", "first"), "github:bob").block();
		assertThatThrownBy(() -> commands.requestRevision(runId, comment("2", "while working"), "github:bob").block())
				.hasMessageContaining("no open pull request");
		assertThatThrownBy(() -> new RevisionRequest("api", "x", "a", " ", null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theRevisionBelongsToTheRoundUntilThePullRequestIsPublishedAgain() {
		commands.requestRevision(runId, comment("1", "first"), "github:bob").block();
		backToPrOpen();
		assertThat(new RunHistory(store.allEvents(runId)).currentRevision()).isEmpty();
	}

	/** Simulates the round finishing: published again, pull request still open. */
	private void backToPrOpen() {
		Run run = store.run(runId);
		Run open = run.transitionTo(RunState.VERIFYING, T0).transitionTo(RunState.REVIEWING, T0)
				.awaitApproval(Gate.PUBLISH, T0).decide(Gate.PUBLISH, GateDecision.APPROVE, T0)
				.transitionTo(RunState.PR_OPEN, T0);
		store.update(run, open, List.of(RunEvent.of(runId, RunEventType.ARTIFACT_PRODUCED, "system",
				Map.of("kind", "pull-request", "url", "https://github.com/acme/shop/pull/7", "content", "u"), T0))).block();
	}
}
