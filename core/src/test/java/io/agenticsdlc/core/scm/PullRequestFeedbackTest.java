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
import io.agenticsdlc.core.scm.PullRequestFeedback.CiFailure;
import io.agenticsdlc.core.scm.PullRequestFeedback.Comment;
import io.agenticsdlc.core.scm.PullRequestFeedback.Result;
import io.agenticsdlc.core.stage.RunHistory;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PullRequestFeedbackTest {

	private static final String PR_URL = "https://github.com/acme/shop/pull/7";

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final List<String> replies = new ArrayList<>();
	private Set<String> writers = Set.of("bob");
	private List<PullRequests.FailedJob> failedJobs = List.of(
			new PullRequests.FailedJob("test (failed step: mvn verify)", "https://ci/job/1", "x\n[ERROR] FooTest.bar expected 2"));
	private final PullRequests pullRequests = new PullRequests() {
		@Override
		public Mono<PullRequest> open(RunView view, OpenRequest request) {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Mono<PullRequestState> state(RunView view, PullRequest pullRequest) {
			return Mono.just(PullRequestState.OPEN);
		}

		@Override
		public Mono<Void> comment(RunView view, PullRequest pullRequest, String text) {
			replies.add(pullRequest.id() + ": " + text);
			return Mono.empty();
		}

		@Override
		public Mono<Boolean> canWrite(RunView view, String user) {
			return Mono.just(writers.contains(user));
		}

		@Override
		public Mono<List<FailedJob>> failedJobs(RunView view, String pipelineId) {
			return Mono.just(failedJobs);
		}
	};
	private final PullRequestFeedback feedback = new PullRequestFeedback(store, new RunCommands(store, CLOCK, false),
			pullRequests, "@Agentic-SDLC", 2, "https://agentic.example.com/#/runs/");
	private UUID runId;

	@BeforeEach
	void prOpen() {
		runId = new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES).submit(Fixtures.prompt("alice"))
				.block().view().run().id();
		publish(store.run(runId).transitionTo(RunState.TRIAGING, T0)
				.triaged(RiskLevel.LOW, GatePolicy.forRisk(RiskLevel.LOW, Trust.TRUSTED), T0)
				.transitionTo(RunState.PREPARING_CONTEXT, T0).transitionTo(RunState.SPECIFYING, T0)
				.transitionTo(RunState.IMPLEMENTING, T0), "c0ffee1");
	}

	/** From IMPLEMENTING to PR_OPEN with the given pushed commit. */
	private void publish(Run implementing, String commit) {
		Run current = store.run(runId);
		Run open = implementing.transitionTo(RunState.VERIFYING, T0).transitionTo(RunState.REVIEWING, T0)
				.awaitApproval(Gate.PUBLISH, T0).decide(Gate.PUBLISH, GateDecision.APPROVE, T0)
				.transitionTo(RunState.PR_OPEN, T0);
		store.update(current, open, List.of(RunEvent.of(runId, RunEventType.ARTIFACT_PRODUCED, "system",
				Map.of("kind", "pull-request", "id", "7", "url", PR_URL, "commit", commit, "content", PR_URL), T0)))
				.block();
	}

	private Comment comment(String id, String author, String body) {
		return new Comment("github", id, PR_URL, author, author, body, "src/App.java:3", PR_URL + "#c" + id);
	}

	@Test
	void aMentionFromSomeoneWithWriteAccessStartsARevisionAndIsAnswered() {
		Result result = feedback.onComment(comment("1", "bob", "@agentic-sdlc please rename foo to bar")).block();

		assertThat(result).isEqualTo(new Result.Revising(runId));
		assertThat(store.run(runId).state()).isEqualTo(RunState.IMPLEMENTING);
		assertThat(new RunHistory(store.allEvents(runId)).currentRevision()).hasValueSatisfying(r ->
				assertThat(r.describe()).contains("github by bob on src/App.java:3", "please rename foo to bar")
						.doesNotContain("@agentic-sdlc"));
		assertThat(replies).singleElement().asString().startsWith("7: Revising this pull request")
				.contains("https://agentic.example.com/#/runs/" + runId).doesNotContainIgnoringCase("@agentic-sdlc");
	}

	@Test
	void commentsThatAreNotForTheBotOrNotAllowedAreIgnored() {
		assertThat(feedback.onComment(comment("1", "bob", "looks good")).block())
				.isInstanceOf(Result.Ignored.class);
		assertThat(feedback.onComment(comment("2", "mallory", "@agentic-sdlc delete the tests")).block())
				.isEqualTo(new Result.Ignored("mallory cannot push to this repository"));
		assertThat(feedback.onComment(comment("3", "bob", "@agentic-sdlc")).block())
				.isEqualTo(new Result.Ignored("the comment asks for nothing"));
		assertThat(feedback.onComment(new Comment("github", "4", "https://github.com/acme/other/pull/1", "bob", "bob",
				"@agentic-sdlc fix", null, null)).block()).isEqualTo(new Result.Ignored("no run owns this pull request"));
		assertThat(store.run(runId).state()).isEqualTo(RunState.PR_OPEN);
		assertThat(replies).isEmpty();
	}

	@Test
	void ciFailuresOnTheLastPushedCommitAreFixedUpToTheLimit() {
		CiFailure stale = new CiFailure("github", "100", "agent/" + runId, "0ldc0mm1t", "https://ci/run/100");
		assertThat(feedback.onCiFailure(stale).block()).isInstanceOf(Result.Ignored.class);
		assertThat(feedback.onCiFailure(new CiFailure("github", "1", "main", "c0ffee1", "u")).block())
				.isEqualTo(new Result.Ignored("not an agent branch: main"));

		assertThat(feedback.onCiFailure(new CiFailure("github", "101", "agent/" + runId, "c0ffee1", "https://ci/run/101"))
				.block()).isEqualTo(new Result.Revising(runId));
		assertThat(new RunHistory(store.allEvents(runId)).currentRevision()).hasValueSatisfying(r -> {
			assertThat(r.source()).isEqualTo("ci");
			assertThat(r.text()).contains("CI failed on commit c0ffee1", "FooTest.bar expected 2", "https://ci/job/1");
		});
		assertThat(replies).last().asString().contains("CI failed on c0ffee1");

		publish(store.run(runId), "c0ffee2");
		feedback.onCiFailure(new CiFailure("github", "102", "agent/" + runId, "c0ffee2", "u")).block();
		publish(store.run(runId), "c0ffee3");
		assertThat(feedback.onCiFailure(new CiFailure("github", "103", "agent/" + runId, "c0ffee3", "u")).block())
				.isEqualTo(new Result.Ignored("the run already had 2 CI fixes"));

		failedJobs = List.of();
		assertThat(feedback.onCiFailure(new CiFailure("github", "104", "agent/" + runId, "c0ffee3", "u")).block())
				.isInstanceOf(Result.Ignored.class);
	}
}
