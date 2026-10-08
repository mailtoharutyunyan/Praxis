package io.agenticsdlc.core.application;

import static io.agenticsdlc.core.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Trust;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunProgressTest {

	private final Run received = Run.start(UUID.randomUUID(), UUID.randomUUID(), T0);

	private Run at(RunState state) {
		Run run = received.transitionTo(RunState.TRIAGING, T0)
				.triaged(RiskLevel.MEDIUM, GatePolicy.forRisk(RiskLevel.MEDIUM, Trust.TRUSTED), T0);
		for (RunState next : List.of(RunState.PREPARING_CONTEXT, RunState.SPECIFYING, RunState.IMPLEMENTING,
				RunState.VERIFYING, RunState.REVIEWING)) {
			if (run.state() == state) {
				return run;
			}
			run = run.transitionTo(next, T0);
		}
		return run;
	}

	private RunEvent event(Run run, RunEventType type, String actor, Map<String, Object> payload) {
		return RunEvent.of(run.id(), type, actor, payload, T0);
	}

	@Test
	void theBarFillsWithinAPhaseAsTheAgentWorks() {
		Run implementing = at(RunState.IMPLEMENTING);
		List<RunEvent> events = new ArrayList<>(List.of(event(implementing, RunEventType.STATE_CHANGED, "system",
				Map.of("from", "AWAITING_APPROVAL", "to", "IMPLEMENTING"))));
		RunProgress start = RunProgress.of(implementing, events);
		assertThat(start.percent()).isEqualTo(30);
		assertThat(start.phase()).isEqualTo("Implementing");
		assertThat(start.step()).isEqualTo(4);
		assertThat(start.activity()).isEqualTo("Starting");

		for (int i = 0; i < 10; i++) {
			events.add(event(implementing, RunEventType.TOOL_CALLED, "agent:coder",
					Map.of("tool", "edit_file", "arguments", "{\"path\":\"services/web/page.txt\"}")));
		}
		RunProgress working = RunProgress.of(implementing, events);
		assertThat(working.percent()).isBetween(31, 64);
		assertThat(working.activity()).startsWith("coder: edit_file").contains("services/web/page.txt");
	}

	@Test
	void gatesHumansAndTheEndAreExplicit() {
		Run atSpec = at(RunState.SPECIFYING).awaitApproval(Gate.SPEC, T0);
		RunProgress waiting = RunProgress.of(atSpec);
		assertThat(waiting.percent()).isEqualTo(30);
		assertThat(waiting.waiting()).isTrue();
		assertThat(waiting.phase()).contains("SPEC gate");

		RunProgress stuck = RunProgress.of(at(RunState.VERIFYING).escalate(T0));
		assertThat(stuck.percent()).isEqualTo(65);
		assertThat(stuck.phase()).isEqualTo("Needs a human");

		Run cancelled = at(RunState.REVIEWING).transitionTo(RunState.CANCELLED, T0);
		RunProgress over = RunProgress.of(cancelled, List.of(event(cancelled, RunEventType.STATE_CHANGED, "bob",
				Map.of("from", "REVIEWING", "to", "CANCELLED"))));
		assertThat(over.percent()).isEqualTo(75);
		assertThat(over.finished()).isTrue();
		assertThat(RunProgress.of(received).percent()).isZero();
		RunProgress cancelledEarly = RunProgress.of(received.transitionTo(RunState.CANCELLED, T0));
		assertThat(cancelledEarly.finished()).isTrue();
		assertThat(cancelledEarly.step()).isEqualTo(1);
	}

	@Test
	void commandsAreDescribed() {
		Run verifying = at(RunState.VERIFYING);
		assertThat(RunProgress.describe(event(verifying, RunEventType.COMMAND_OUTPUT, "system:sandbox",
				Map.of("command", "cd 'web' && npm test", "exitCode", 1, "timedOut", false, "service", "web"))))
				.isEqualTo("ran `cd 'web' && npm test` → exit 1 (web)");
	}
}
