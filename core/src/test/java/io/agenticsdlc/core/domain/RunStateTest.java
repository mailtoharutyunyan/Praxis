package io.agenticsdlc.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunStateTest {

	@ParameterizedTest
	@EnumSource(value = RunState.class, names = { "DONE", "FAILED", "CANCELLED" })
	void terminalStatesNeverChange(RunState terminal) {
		for (RunState next : RunState.values()) {
			assertThat(terminal.canTransitionTo(next)).as("%s -> %s", terminal, next).isFalse();
		}
	}

	@ParameterizedTest
	@EnumSource(value = RunState.class, names = { "DONE", "FAILED", "CANCELLED" }, mode = EnumSource.Mode.EXCLUDE)
	void anyLiveRunCanBeCancelledFailedOrEscalated(RunState live) {
		assertThat(live.canTransitionTo(RunState.CANCELLED)).isTrue();
		assertThat(live.canTransitionTo(RunState.FAILED)).isTrue();
		assertThat(live.canTransitionTo(RunState.NEEDS_HUMAN)).isEqualTo(live.isWorking());
	}

	@Test
	void happyPathIsAllowed() {
		RunState[] path = { RunState.RECEIVED, RunState.TRIAGING, RunState.PREPARING_CONTEXT, RunState.SPECIFYING,
				RunState.IMPLEMENTING, RunState.VERIFYING, RunState.REVIEWING, RunState.AWAITING_APPROVAL,
				RunState.PUBLISHING, RunState.PR_OPEN, RunState.DONE };
		for (int i = 1; i < path.length; i++) {
			assertThat(path[i - 1].canTransitionTo(path[i])).as("%s -> %s", path[i - 1], path[i]).isTrue();
		}
	}

	@Test
	void cannotSkipVerificationOrPublishWithoutApproval() {
		assertThat(RunState.IMPLEMENTING.canTransitionTo(RunState.REVIEWING)).isFalse();
		assertThat(RunState.REVIEWING.canTransitionTo(RunState.PUBLISHING)).isFalse();
		assertThat(RunState.VERIFYING.canTransitionTo(RunState.PUBLISHING)).isFalse();
	}

	@Test
	void gateTargetsAreReachableFromAwaitingApproval() {
		for (Gate gate : Gate.values()) {
			assertThat(gate.openedFrom().canTransitionTo(RunState.AWAITING_APPROVAL)).isTrue();
			assertThat(RunState.AWAITING_APPROVAL.canTransitionTo(gate.onApproved())).isTrue();
			assertThat(RunState.AWAITING_APPROVAL.canTransitionTo(gate.onChangesRequested())).isTrue();
		}
	}
}
