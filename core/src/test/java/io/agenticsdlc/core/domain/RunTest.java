package io.agenticsdlc.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunTest {

	private static final Instant T0 = Instant.parse("2026-10-08T10:00:00Z");
	private static final Instant T1 = T0.plusSeconds(1);

	private final Run received = Run.start(UUID.randomUUID(), UUID.randomUUID(), T0);

	private Run triagedAs(RiskLevel risk) {
		return received.transitionTo(RunState.TRIAGING, T1)
				.triaged(risk, GatePolicy.forRisk(risk, Trust.TRUSTED), T1);
	}

	private Run specifying(RiskLevel risk) {
		return triagedAs(risk).transitionTo(RunState.PREPARING_CONTEXT, T1).transitionTo(RunState.SPECIFYING, T1);
	}

	private Run reviewing(RiskLevel risk) {
		return specifying(risk).transitionTo(RunState.IMPLEMENTING, T1)
				.transitionTo(RunState.VERIFYING, T1)
				.transitionTo(RunState.REVIEWING, T1);
	}

	@Test
	void startsReceivedWithNothingPending() {
		assertThat(received.state()).isEqualTo(RunState.RECEIVED);
		assertThat(received.usage()).isEqualTo(Usage.ZERO);
		assertThat(received.pendingGate()).isNull();
		assertThat(received.resumeState()).isNull();
		assertThat(received.version()).isZero();
	}

	@Test
	void rejectsIllegalTransition() {
		assertThatThrownBy(() -> received.transitionTo(RunState.PUBLISHING, T1))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("RECEIVED")
				.hasMessageContaining("PUBLISHING");
	}

	@ParameterizedTest
	@EnumSource(value = RunState.class, names = { "AWAITING_APPROVAL", "NEEDS_HUMAN" })
	void waitingStatesNeedDedicatedMethods(RunState waiting) {
		assertThatThrownBy(() -> received.transitionTo(waiting, T1)).isInstanceOf(IllegalStateException.class);
	}

	@Nested
	class Gates {

		@Test
		void approvedSpecLeadsToImplementation() {
			Run awaiting = specifying(RiskLevel.MEDIUM).awaitApproval(Gate.SPEC, T1);
			assertThat(awaiting.state()).isEqualTo(RunState.AWAITING_APPROVAL);
			assertThat(awaiting.pendingGate()).isEqualTo(Gate.SPEC);

			Run approved = awaiting.decide(Gate.SPEC, GateDecision.APPROVE, T1);
			assertThat(approved.state()).isEqualTo(RunState.IMPLEMENTING);
			assertThat(approved.pendingGate()).isNull();
		}

		@Test
		void changesRequestedReturnToReworkStage() {
			Run awaiting = reviewing(RiskLevel.LOW).awaitApproval(Gate.PUBLISH, T1);
			assertThat(awaiting.decide(Gate.PUBLISH, GateDecision.REQUEST_CHANGES, T1).state())
					.isEqualTo(RunState.IMPLEMENTING);
		}

		@Test
		void rejectionCancels() {
			Run awaiting = reviewing(RiskLevel.LOW).awaitApproval(Gate.PUBLISH, T1);
			assertThat(awaiting.decide(Gate.PUBLISH, GateDecision.REJECT, T1).state()).isEqualTo(RunState.CANCELLED);
		}

		@Test
		void cannotLeaveGateByPlainTransition() {
			Run awaiting = specifying(RiskLevel.MEDIUM).awaitApproval(Gate.SPEC, T1);
			assertThatThrownBy(() -> awaiting.transitionTo(RunState.PUBLISHING, T1))
					.isInstanceOf(IllegalStateException.class);
			assertThat(awaiting.transitionTo(RunState.CANCELLED, T1).state()).isEqualTo(RunState.CANCELLED);
		}

		@Test
		void decisionMustNameThePendingGate() {
			Run awaiting = specifying(RiskLevel.MEDIUM).awaitApproval(Gate.SPEC, T1);
			assertThatThrownBy(() -> awaiting.decide(Gate.PUBLISH, GateDecision.APPROVE, T1))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("PUBLISH");
		}

		@Test
		void gateOpensOnlyFromItsOwnStage() {
			Run verifying = specifying(RiskLevel.HIGH).transitionTo(RunState.IMPLEMENTING, T1)
					.transitionTo(RunState.VERIFYING, T1);
			assertThatThrownBy(() -> verifying.awaitApproval(Gate.PUBLISH, T1))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("REVIEWING");
		}

		@Test
		void gateOpensOnlyWhenPolicyRequiresIt() {
			assertThatThrownBy(() -> specifying(RiskLevel.LOW).awaitApproval(Gate.SPEC, T1))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("does not require");
		}

		@Test
		void untriagedRunHasNoGates() {
			assertThatThrownBy(() -> received.awaitApproval(Gate.PUBLISH, T1)).isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	class Escalation {

		@Test
		void resumesExactlyWhereItStopped() {
			Run implementing = specifying(RiskLevel.LOW).transitionTo(RunState.IMPLEMENTING, T1);
			Run escalated = implementing.escalate(T1);
			assertThat(escalated.state()).isEqualTo(RunState.NEEDS_HUMAN);
			assertThat(escalated.resumeState()).isEqualTo(RunState.IMPLEMENTING);

			Run resumed = escalated.resume(T1);
			assertThat(resumed.state()).isEqualTo(RunState.IMPLEMENTING);
			assertThat(resumed.resumeState()).isNull();
		}

		@Test
		void cannotJumpFromNeedsHumanToPublishing() {
			Run escalated = specifying(RiskLevel.LOW).escalate(T1);
			assertThatThrownBy(() -> escalated.transitionTo(RunState.PUBLISHING, T1))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void onlyWorkingRunsEscalate() {
			Run awaiting = specifying(RiskLevel.MEDIUM).awaitApproval(Gate.SPEC, T1);
			assertThatThrownBy(() -> awaiting.escalate(T1)).isInstanceOf(IllegalStateException.class);
		}

		@Test
		void resumeRequiresNeedsHuman() {
			assertThatThrownBy(() -> received.resume(T1)).isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	class Risk {

		@Test
		void triageHappensOnceWhileTriaging() {
			GatePolicy policy = GatePolicy.forRisk(RiskLevel.LOW, Trust.TRUSTED);
			assertThatThrownBy(() -> received.triaged(RiskLevel.LOW, policy, T1))
					.isInstanceOf(IllegalStateException.class);
			Run triaged = triagedAs(RiskLevel.LOW);
			assertThatThrownBy(() -> triaged.triaged(RiskLevel.LOW, policy, T1))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void raisingRiskAddsGates() {
			Run raised = specifying(RiskLevel.LOW)
					.raiseRisk(RiskLevel.HIGH, GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED), T1);
			assertThat(raised.risk()).isEqualTo(RiskLevel.HIGH);
			assertThat(raised.gatePolicy().gates()).containsExactly(Gate.SPEC, Gate.IMPLEMENTATION, Gate.PUBLISH);
		}

		@Test
		void riskNeverGoesDown() {
			Run high = specifying(RiskLevel.HIGH);
			assertThatThrownBy(() -> high.raiseRisk(RiskLevel.LOW, GatePolicy.forRisk(RiskLevel.LOW, Trust.TRUSTED), T1))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void riskIsFixedOncePublishing() {
			Run publishing = reviewing(RiskLevel.LOW).awaitApproval(Gate.PUBLISH, T1)
					.decide(Gate.PUBLISH, GateDecision.APPROVE, T1);
			assertThat(publishing.state()).isEqualTo(RunState.PUBLISHING);
			assertThatThrownBy(() -> publishing.raiseRisk(RiskLevel.HIGH,
					GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED), T1))
					.isInstanceOf(IllegalStateException.class);
		}

		@Test
		void cannotRaiseBeforeTriage() {
			assertThatThrownBy(() -> received.raiseRisk(RiskLevel.HIGH,
					GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED), T1))
					.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void countersAndUsageAccumulate() {
		Run run = received.withFixIteration(T1).withFixIteration(T1).withReviewLoop(T1)
				.addUsage(new Usage(100, 20, 1000, 50, 500), T1)
				.addUsage(new Usage(10, 5, 0, 0, 50), T1);
		assertThat(run.fixIterations()).isEqualTo(2);
		assertThat(run.reviewLoops()).isEqualTo(1);
		assertThat(run.usage()).isEqualTo(new Usage(110, 25, 1000, 50, 550));
		assertThat(run.usage().totalTokens()).isEqualTo(1185);
	}

	@Test
	void rejectsInconsistentState() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> new Run(id, id, RunState.AWAITING_APPROVAL, null, null, null, null, 0, 0, Usage.ZERO,
				0, T0, T0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Run(id, id, RunState.NEEDS_HUMAN, null, null, null, RunState.DONE, 0, 0,
				Usage.ZERO, 0, T0, T0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Run(id, id, RunState.RECEIVED, RiskLevel.LOW, null, null, null, 0, 0, Usage.ZERO,
				0, T0, T0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Run(id, id, RunState.RECEIVED, null, null, null, null, -1, 0, Usage.ZERO, 0, T0,
				T0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void usageRejectsNegatives() {
		assertThatThrownBy(() -> new Usage(0, 0, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
