package io.agenticsdlc.core.engine;

import static io.agenticsdlc.core.support.Fixtures.LIMITS;
import static io.agenticsdlc.core.support.Fixtures.REPO;
import static io.agenticsdlc.core.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.domain.Usage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransitionsTest {

	private static final StageOutcome.Completed DONE = StageOutcome.Completed.free();

	private final Task trusted = task(Trust.TRUSTED);

	private static Task task(Trust trust) {
		return new Task(UUID.randomUUID(), trust == Trust.TRUSTED ? TaskOrigin.PROMPT : TaskOrigin.JIRA, null, "t", "d",
				REPO, null, trust, "alice", null, T0);
	}

	private Run at(RunState state, RiskLevel risk) {
		Run run = Run.start(UUID.randomUUID(), trusted.id(), T0).transitionTo(RunState.TRIAGING, T0)
				.triaged(risk, GatePolicy.forRisk(risk, Trust.TRUSTED), T0);
		RunState[] path = { RunState.PREPARING_CONTEXT, RunState.SPECIFYING, RunState.IMPLEMENTING, RunState.VERIFYING,
				RunState.REVIEWING };
		for (RunState next : path) {
			if (run.state() == state) {
				return run;
			}
			run = run.transitionTo(next, T0);
		}
		if (run.state() != state) {
			throw new IllegalArgumentException("unsupported fixture state " + state);
		}
		return run;
	}

	private Transitions.Step apply(Run run, StageOutcome outcome) {
		return Transitions.apply(run, trusted, outcome, LIMITS, T0);
	}

	@Test
	void receivedStartsTriage() {
		Transitions.Step step = Transitions.start(Run.start(UUID.randomUUID(), trusted.id(), T0), T0);
		assertThat(step.next().state()).isEqualTo(RunState.TRIAGING);
		assertThat(step.events()).extracting(RunEvent::type).containsExactly(RunEventType.STATE_CHANGED);
	}

	@Test
	void startRequiresReceived() {
		assertThatThrownBy(() -> Transitions.start(at(RunState.SPECIFYING, RiskLevel.LOW), T0))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void triageSetsRiskAndPolicyFromTrust() {
		Task untrusted = task(Trust.UNTRUSTED);
		Run triaging = Run.start(UUID.randomUUID(), untrusted.id(), T0).transitionTo(RunState.TRIAGING, T0);
		Transitions.Step step = Transitions.apply(triaging, untrusted,
				new StageOutcome.Triaged(RiskLevel.LOW, "typo fix", Usage.ZERO), LIMITS, T0);

		assertThat(step.next().state()).isEqualTo(RunState.PREPARING_CONTEXT);
		assertThat(step.next().risk()).isEqualTo(RiskLevel.LOW);
		assertThat(step.next().gatePolicy().gates()).containsExactly(Gate.SPEC, Gate.PUBLISH);
		assertThat(step.events()).extracting(RunEvent::type).containsExactly(RunEventType.STAGE_COMPLETED,
				RunEventType.TRIAGED, RunEventType.STATE_CHANGED);
	}

	@Test
	void planReviewStopsLowRiskTaskAtSpecGate() {
		Task reviewed = new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "t", "d", REPO, null, Trust.TRUSTED,
				"alice", null, T0, java.util.List.of(), true);
		Run triaging = Run.start(UUID.randomUUID(), reviewed.id(), T0).transitionTo(RunState.TRIAGING, T0);
		Transitions.Step step = Transitions.apply(triaging, reviewed,
				new StageOutcome.Triaged(RiskLevel.LOW, "small change", Usage.ZERO), LIMITS, T0);

		assertThat(step.next().risk()).isEqualTo(RiskLevel.LOW);
		assertThat(step.next().gatePolicy().gates()).containsExactly(Gate.SPEC, Gate.PUBLISH);

		Run specifying = step.next().transitionTo(RunState.SPECIFYING, T0);
		Transitions.Step specified = Transitions.apply(specifying, reviewed, DONE, LIMITS, T0);
		assertThat(specified.next().state()).isEqualTo(RunState.AWAITING_APPROVAL);
		assertThat(specified.next().pendingGate()).isEqualTo(Gate.SPEC);
	}

	@Test
	void planReviewKeepsHighRiskGates() {
		Task reviewed = new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "t", "d", REPO, null, Trust.TRUSTED,
				"alice", null, T0, java.util.List.of(), true);
		Run triaging = Run.start(UUID.randomUUID(), reviewed.id(), T0).transitionTo(RunState.TRIAGING, T0);
		Transitions.Step step = Transitions.apply(triaging, reviewed,
				new StageOutcome.Triaged(RiskLevel.HIGH, "auth", Usage.ZERO), LIMITS, T0);
		assertThat(step.next().gatePolicy().gates()).containsExactly(Gate.SPEC, Gate.IMPLEMENTATION, Gate.PUBLISH);
	}

	@Test
	void withoutPlanReviewLowRiskTrustedTaskOnlyHasPublishGate() {
		Run triaging = Run.start(UUID.randomUUID(), trusted.id(), T0).transitionTo(RunState.TRIAGING, T0);
		Transitions.Step step = apply(triaging, new StageOutcome.Triaged(RiskLevel.LOW, "typo", Usage.ZERO));
		assertThat(step.next().gatePolicy().gates()).containsExactly(Gate.PUBLISH);
	}

	@Test
	void triageWithoutRiskFails() {
		Run triaging = Run.start(UUID.randomUUID(), trusted.id(), T0).transitionTo(RunState.TRIAGING, T0);
		assertThat(apply(triaging, DONE).next().state()).isEqualTo(RunState.FAILED);
	}

	@Test
	void triageResultOutsideTriageFails() {
		Transitions.Step step = apply(at(RunState.SPECIFYING, RiskLevel.LOW),
				new StageOutcome.Triaged(RiskLevel.HIGH, "late", Usage.ZERO));
		assertThat(step.next().state()).isEqualTo(RunState.FAILED);
	}

	@Test
	void specGateOpensOnlyWhenRequired() {
		assertThat(apply(at(RunState.SPECIFYING, RiskLevel.LOW), DONE).next().state())
				.isEqualTo(RunState.IMPLEMENTING);

		Transitions.Step gated = apply(at(RunState.SPECIFYING, RiskLevel.MEDIUM), DONE);
		assertThat(gated.next().state()).isEqualTo(RunState.AWAITING_APPROVAL);
		assertThat(gated.next().pendingGate()).isEqualTo(Gate.SPEC);
		assertThat(gated.events()).extracting(RunEvent::type).contains(RunEventType.GATE_OPENED);
	}

	@Test
	void implementationGateOnlyForHighRisk() {
		assertThat(apply(at(RunState.VERIFYING, RiskLevel.MEDIUM), DONE).next().state())
				.isEqualTo(RunState.REVIEWING);
		assertThat(apply(at(RunState.VERIFYING, RiskLevel.HIGH), DONE).next().pendingGate())
				.isEqualTo(Gate.IMPLEMENTATION);
	}

	@Test
	void reviewAlwaysEndsAtPublishGate() {
		for (RiskLevel risk : RiskLevel.values()) {
			assertThat(apply(at(RunState.REVIEWING, risk), DONE).next().pendingGate()).isEqualTo(Gate.PUBLISH);
		}
	}

	@Test
	void linearStagesAdvance() {
		assertThat(apply(at(RunState.PREPARING_CONTEXT, RiskLevel.LOW), DONE).next().state())
				.isEqualTo(RunState.SPECIFYING);
		assertThat(apply(at(RunState.IMPLEMENTING, RiskLevel.LOW), DONE).next().state())
				.isEqualTo(RunState.VERIFYING);
		Run publishing = at(RunState.REVIEWING, RiskLevel.LOW).awaitApproval(Gate.PUBLISH, T0)
				.decide(Gate.PUBLISH, io.agenticsdlc.core.domain.GateDecision.APPROVE, T0);
		assertThat(apply(publishing, DONE).next().state()).isEqualTo(RunState.PR_OPEN);
	}

	@Test
	void failedVerificationLoopsUntilLimitThenEscalates() {
		Run run = at(RunState.VERIFYING, RiskLevel.LOW);
		for (int i = 0; i < LIMITS.maxFixIterations(); i++) {
			run = apply(run, new StageOutcome.NeedsRework("2 tests fail", Usage.ZERO)).next();
			assertThat(run.state()).isEqualTo(RunState.IMPLEMENTING);
			run = run.transitionTo(RunState.VERIFYING, T0);
		}
		Transitions.Step exhausted = apply(run, new StageOutcome.NeedsRework("still failing", Usage.ZERO));
		assertThat(exhausted.next().state()).isEqualTo(RunState.NEEDS_HUMAN);
		assertThat(exhausted.next().resumeState()).isEqualTo(RunState.VERIFYING);
		// A human resuming gets another round of fixes instead of an immediate re-escalation.
		Run resumed = exhausted.next().resume(T0);
		assertThat(resumed.state()).isEqualTo(RunState.VERIFYING);
		assertThat(resumed.fixIterations()).isZero();
		assertThat(apply(resumed, new StageOutcome.NeedsRework("still red", Usage.ZERO)).next().state())
				.isEqualTo(RunState.IMPLEMENTING);
		assertThat(exhausted.next().fixIterations()).isEqualTo(LIMITS.maxFixIterations());
	}

	@Test
	void reviewChangesLoopUntilLimitThenEscalate() {
		Run run = at(RunState.REVIEWING, RiskLevel.LOW);
		for (int i = 0; i < LIMITS.maxReviewLoops(); i++) {
			run = apply(run, new StageOutcome.NeedsRework("missing tests", Usage.ZERO)).next();
			assertThat(run.state()).isEqualTo(RunState.IMPLEMENTING);
			run = run.transitionTo(RunState.VERIFYING, T0).transitionTo(RunState.REVIEWING, T0);
		}
		assertThat(apply(run, new StageOutcome.NeedsRework("again", Usage.ZERO)).next().state())
				.isEqualTo(RunState.NEEDS_HUMAN);
	}

	@Test
	void reworkOutsideVerifyOrReviewFails() {
		assertThat(apply(at(RunState.IMPLEMENTING, RiskLevel.LOW), new StageOutcome.NeedsRework("?", Usage.ZERO))
				.next().state()).isEqualTo(RunState.FAILED);
	}

	@Test
	void escalateAndFailAreRecorded() {
		Transitions.Step escalated = apply(at(RunState.IMPLEMENTING, RiskLevel.LOW),
				new StageOutcome.Escalate("which DB?", Usage.ZERO));
		assertThat(escalated.next().state()).isEqualTo(RunState.NEEDS_HUMAN);
		assertThat(escalated.events()).extracting(RunEvent::type).contains(RunEventType.ERROR);

		assertThat(apply(at(RunState.IMPLEMENTING, RiskLevel.LOW), new StageOutcome.Failed("repo gone", Usage.ZERO))
				.next().state()).isEqualTo(RunState.FAILED);
	}

	@Test
	void usageIsRecordedAndBudgetEscalatesBeforeNextStage() {
		Usage expensive = new Usage(10, 10, 0, 0, LIMITS.maxCostMicroUsd() + 1);
		Transitions.Step step = apply(at(RunState.IMPLEMENTING, RiskLevel.LOW), new StageOutcome.Completed(expensive,
				java.util.Map.of("files", 3)));
		assertThat(step.next().state()).isEqualTo(RunState.NEEDS_HUMAN);
		assertThat(step.next().resumeState()).isEqualTo(RunState.VERIFYING);
		assertThat(step.next().usage()).isEqualTo(expensive);
		assertThat(step.events()).extracting(RunEvent::type).contains(RunEventType.USAGE_RECORDED);

		Usage manyTokens = new Usage(LIMITS.maxTokens(), 1, 0, 0, 0);
		assertThat(apply(at(RunState.IMPLEMENTING, RiskLevel.LOW), new StageOutcome.Completed(manyTokens, null))
				.next().state()).isEqualTo(RunState.NEEDS_HUMAN);
	}

	@Test
	void budgetDoesNotInterruptAGate() {
		Usage expensive = new Usage(0, 0, 0, 0, LIMITS.maxCostMicroUsd() + 1);
		assertThat(apply(at(RunState.REVIEWING, RiskLevel.LOW), new StageOutcome.Completed(expensive, null))
				.next().state()).isEqualTo(RunState.AWAITING_APPROVAL);
	}

	@Test
	void rejectsOutcomeForNonStageStates() {
		Run received = Run.start(UUID.randomUUID(), trusted.id(), T0);
		assertThatThrownBy(() -> apply(received, DONE)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void limitsMustBePositive() {
		assertThatThrownBy(() -> new RunLimits(1, 1, 0, 1, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
