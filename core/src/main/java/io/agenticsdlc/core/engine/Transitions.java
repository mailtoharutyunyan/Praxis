package io.agenticsdlc.core.engine;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.Usage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The routing rules of the pipeline: given a run, the outcome of its current stage and the limits, decide the
 * next state and the events that explain it. Pure and deterministic, so every path is unit-testable.
 */
public final class Transitions {

	public static final String SYSTEM = "system";

	private Transitions() {
	}

	/** The run after a step, plus the events to append atomically with it. */
	public record Step(Run next, List<RunEvent> events) {
		public Step {
			Objects.requireNonNull(next, "next");
			events = List.copyOf(events);
		}
	}

	/** RECEIVED needs no handler: it only marks that the run entered the pipeline. */
	public static Step start(Run run, Instant now) {
		if (run.state() != RunState.RECEIVED) {
			throw new IllegalStateException("run " + run.id() + " is not RECEIVED");
		}
		Recorder recorder = new Recorder(run, now);
		return recorder.moveTo(RunState.TRIAGING).step();
	}

	public static Step apply(Run run, Task task, StageOutcome outcome, RunLimits limits, Instant now) {
		Objects.requireNonNull(outcome, "outcome");
		RunState stage = run.state();
		if (!stage.isWorking() || stage == RunState.RECEIVED) {
			throw new IllegalStateException("run " + run.id() + " is not in a stage that produces outcomes: " + stage);
		}
		Recorder recorder = new Recorder(run, now);
		recorder.recordUsage(outcome.usage());
		recorder.event(RunEventType.STAGE_COMPLETED, payload("stage", stage.name(), "outcome",
				outcome.getClass().getSimpleName(), "detail", detail(outcome)));

		switch (outcome) {
			case StageOutcome.Escalate e -> recorder.escalate(e.reason());
			case StageOutcome.Failed f -> recorder.fail(f.reason());
			case StageOutcome.Triaged t -> {
				if (stage != RunState.TRIAGING) {
					recorder.fail("handler for " + stage + " returned a triage result");
				}
				else {
					GatePolicy policy = GatePolicy.forRisk(t.risk(), task.trust());
					recorder.triaged(t, policy);
					recorder.moveTo(RunState.PREPARING_CONTEXT);
				}
			}
			case StageOutcome.NeedsRework r -> rework(recorder, stage, r.reason(), limits);
			case StageOutcome.Completed c -> completed(recorder, stage);
		}

		recorder.enforceBudget(limits);
		return recorder.step();
	}

	private static void completed(Recorder recorder, RunState stage) {
		switch (stage) {
			case TRIAGING -> recorder.fail("triage completed without a risk assessment");
			case PREPARING_CONTEXT -> recorder.moveTo(RunState.SPECIFYING);
			case SPECIFYING -> recorder.gateOrMove(Gate.SPEC, RunState.IMPLEMENTING);
			case IMPLEMENTING -> recorder.moveTo(RunState.VERIFYING);
			case VERIFYING -> recorder.gateOrMove(Gate.IMPLEMENTATION, RunState.REVIEWING);
			case REVIEWING -> recorder.gateOrMove(Gate.PUBLISH, null);
			case PUBLISHING -> recorder.moveTo(RunState.PR_OPEN);
			default -> throw new IllegalStateException("no completion rule for " + stage);
		}
	}

	private static void rework(Recorder recorder, RunState stage, String reason, RunLimits limits) {
		Run run = recorder.run;
		switch (stage) {
			case VERIFYING -> {
				if (run.fixIterations() >= limits.maxFixIterations()) {
					recorder.escalate("verification still failing after " + run.fixIterations()
							+ " fix iterations: " + reason);
				}
				else {
					recorder.run = run.withFixIteration(recorder.now);
					recorder.moveTo(RunState.IMPLEMENTING);
				}
			}
			case REVIEWING -> {
				if (run.reviewLoops() >= limits.maxReviewLoops()) {
					recorder.escalate("reviewer still requests changes after " + run.reviewLoops() + " loops: " + reason);
				}
				else {
					recorder.run = run.withReviewLoop(recorder.now);
					recorder.moveTo(RunState.IMPLEMENTING);
				}
			}
			default -> recorder.fail("handler for " + stage + " requested rework, which only VERIFYING and REVIEWING may");
		}
	}

	private static String detail(StageOutcome outcome) {
		return switch (outcome) {
			case StageOutcome.Completed c -> c.summary().isEmpty() ? null : String.valueOf(c.summary());
			case StageOutcome.Triaged t -> t.rationale();
			case StageOutcome.NeedsRework r -> r.reason();
			case StageOutcome.Escalate e -> e.reason();
			case StageOutcome.Failed f -> f.reason();
		};
	}

	static Map<String, Object> payload(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	/** Accumulates the run and the events of one step. */
	private static final class Recorder {

		private Run run;
		private final Instant now;
		private final List<RunEvent> events = new ArrayList<>();

		Recorder(Run run, Instant now) {
			this.run = run;
			this.now = now;
		}

		void event(RunEventType type, Map<String, Object> payload) {
			events.add(RunEvent.of(run.id(), type, SYSTEM, payload, now));
		}

		void recordUsage(Usage usage) {
			if (usage.equals(Usage.ZERO)) {
				return;
			}
			run = run.addUsage(usage, now);
			event(RunEventType.USAGE_RECORDED, payload("inputTokens", usage.inputTokens(), "outputTokens",
					usage.outputTokens(), "cacheReadTokens", usage.cacheReadTokens(), "cacheWriteTokens",
					usage.cacheWriteTokens(), "costMicroUsd", usage.costMicroUsd(), "runTotalTokens",
					run.usage().totalTokens(), "runCostMicroUsd", run.usage().costMicroUsd()));
		}

		void triaged(StageOutcome.Triaged triage, GatePolicy policy) {
			run = run.triaged(triage.risk(), policy, now);
			event(RunEventType.TRIAGED, payload("risk", triage.risk().name(), "gates",
					policy.gates().stream().map(Enum::name).toList(), "rationale", triage.rationale()));
		}

		Recorder moveTo(RunState next) {
			RunState from = run.state();
			run = run.transitionTo(next, now);
			stateChanged(from);
			return this;
		}

		/** Open {@code gate} if the policy requires it, else continue to {@code otherwise}. */
		void gateOrMove(Gate gate, RunState otherwise) {
			if (run.gatePolicy().requires(gate)) {
				RunState from = run.state();
				run = run.awaitApproval(gate, now);
				event(RunEventType.GATE_OPENED, payload("gate", gate.name()));
				stateChanged(from);
			}
			else {
				moveTo(Objects.requireNonNull(otherwise, () -> "gate " + gate + " is mandatory"));
			}
		}

		void escalate(String reason) {
			RunState from = run.state();
			event(RunEventType.ERROR, payload("kind", "ESCALATED", "reason", reason));
			run = run.escalate(now);
			stateChanged(from);
		}

		void fail(String reason) {
			event(RunEventType.ERROR, payload("kind", "FAILED", "reason", reason));
			moveTo(RunState.FAILED);
		}

		/** After the transition: a run over budget is handed to a human before it starts the next stage. */
		void enforceBudget(RunLimits limits) {
			if (!run.state().isWorking()) {
				return;
			}
			Usage used = run.usage();
			if (used.budgetTokens() > limits.maxTokens()) {
				escalate("token budget exceeded: " + used.budgetTokens() + " > " + limits.maxTokens()
						+ " (cache reads count a tenth); raise agentic.limits.max-tokens or split the task");
			}
			else if (used.costMicroUsd() > limits.maxCostMicroUsd()) {
				escalate("cost budget exceeded: " + used.costMicroUsd() + " > " + limits.maxCostMicroUsd()
						+ " micro-USD");
			}
		}

		private void stateChanged(RunState from) {
			event(RunEventType.STATE_CHANGED, payload("from", from.name(), "to", run.state().name()));
		}

		Step step() {
			return new Step(run, events);
		}
	}
}
