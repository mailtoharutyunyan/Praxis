package io.agenticsdlc.core.eval;

import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.Sandbox;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Replays historical tasks through the real pipeline (same stages, models, sandbox and limits as production) and
 * grades the outcome with hidden tests. Gates before publishing are approved automatically; the run is stopped at the
 * PUBLISH gate and graded in its sandbox, so nothing is ever pushed. Runs are cancelled and their sandboxes removed
 * afterwards.
 */
public final class EvalHarness {

	public static final String REQUESTER = "eval";
	public static final String APPROVER = "eval-approver";

	private final TaskIntake intake;
	private final RunQueries queries;
	private final RunCommands commands;
	private final Sandbox sandbox;
	private final Clock clock;
	private final Duration pollInterval;
	private final Duration trialTimeout;
	private final Duration commandTimeout;

	public EvalHarness(TaskIntake intake, RunQueries queries, RunCommands commands, Sandbox sandbox, Clock clock,
			Duration pollInterval, Duration trialTimeout, Duration commandTimeout) {
		this.intake = Objects.requireNonNull(intake, "intake");
		this.queries = Objects.requireNonNull(queries, "queries");
		this.commands = Objects.requireNonNull(commands, "commands");
		this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
		this.trialTimeout = Objects.requireNonNull(trialTimeout, "trialTimeout");
		this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
	}

	/** Runs every case {@code trials} times, {@code concurrency} trials at a time. */
	public Mono<EvalReport> run(String suite, List<EvalCase> cases, int trials, int concurrency) {
		List<Map.Entry<EvalCase, Integer>> work = new ArrayList<>();
		for (EvalCase evalCase : cases) {
			for (int i = 1; i <= trials; i++) {
				work.add(Map.entry(evalCase, i));
			}
		}
		return Flux.fromIterable(work)
				.flatMap(entry -> trial(entry.getKey(), entry.getValue()), Math.max(1, concurrency))
				.collectList()
				.map(results -> new EvalReport(suite, trials, results));
	}

	Mono<EvalTrial> trial(EvalCase evalCase, int trial) {
		Instant started = clock.instant();
		NewTask task = new NewTask(TaskOrigin.PROMPT, evalCase.id(), evalCase.title(), evalCase.description(),
				evalCase.repository(), evalCase.ref(), REQUESTER, "eval:" + evalCase.id() + ":" + trial + ":" + UUID.randomUUID());
		return intake.submit(task)
				.map(submission -> submission.view().run().id())
				.flatMap(runId -> drive(runId)
						.flatMap(run -> run.pendingGate() == Gate.PUBLISH ? grade(evalCase, run)
								: Mono.just(List.of("pipeline stopped in " + run.state())))
						.flatMap(failed -> summarize(evalCase, trial, runId, failed, started))
						.timeout(trialTimeout, Mono.defer(() -> summarize(evalCase, trial, runId,
								List.of("trial timed out after " + trialTimeout), started)))
						.flatMap(result -> commands.cancel(runId, "evaluation finished", APPROVER)
								.onErrorResume(e -> Mono.empty())
								// Trials can run back to back; free their containers now rather than at the next cleanup.
								.then(sandbox.destroy(runId).onErrorResume(e -> Mono.empty()))
								.thenReturn(result)));
	}

	/** Polls the run, approving SPEC and IMPLEMENTATION gates, until it waits at PUBLISH or stops. */
	private Mono<Run> drive(UUID runId) {
		return Flux.interval(Duration.ZERO, pollInterval)
				.concatMap(tick -> queries.get(runId).map(view -> view.run()))
				.concatMap(run -> {
					if (run.state() == RunState.AWAITING_APPROVAL && run.pendingGate() != Gate.PUBLISH) {
						return commands.decide(runId, run.pendingGate(), GateDecision.APPROVE, "auto-approved for evaluation",
								APPROVER).thenReturn(run).onErrorResume(e -> Mono.just(run));
					}
					return Mono.just(run);
				})
				.filter(run -> run.pendingGate() == Gate.PUBLISH || run.state() == RunState.NEEDS_HUMAN
						|| run.state().isTerminal())
				.next();
	}

	/** Adds the hidden tests and runs the grading commands; returns the failures (empty = pass). */
	private Mono<List<String>> grade(EvalCase evalCase, Run run) {
		UUID runId = run.id();
		Mono<Void> hidden = Flux.fromIterable(evalCase.hiddenFiles().entrySet())
				.concatMap(file -> sandbox.writeFile(runId, file.getKey(), file.getValue()))
				.then();
		List<String> checks = new ArrayList<>(evalCase.failToPass());
		checks.addAll(evalCase.passToPass());
		return hidden.thenMany(Flux.fromIterable(checks).concatMap(command -> sandbox.exec(runId, command, commandTimeout)))
				.filter(result -> !result.succeeded())
				.map(EvalHarness::describe)
				.collectList();
	}

	private Mono<EvalTrial> summarize(EvalCase evalCase, int trial, UUID runId, List<String> failures, Instant started) {
		return Mono.zip(queries.get(runId), queries.events(runId, 0, Integer.MAX_VALUE).collectList()).map(tuple -> {
			Run run = tuple.getT1().run();
			int toolCalls = (int) tuple.getT2().stream().filter(e -> e.type() == RunEventType.TOOL_CALLED).count();
			String note = tuple.getT2().stream().filter(e -> e.type() == RunEventType.ERROR)
					.map(e -> String.valueOf(e.payload().get("reason"))).reduce((a, b) -> b).orElse(null);
			boolean reachedPublish = run.pendingGate() == Gate.PUBLISH;
			boolean passed = reachedPublish && failures.isEmpty();
			if (!reachedPublish && note == null && !failures.isEmpty()) {
				note = failures.getFirst();
			}
			return new EvalTrial(evalCase.id(), trial, runId, passed, reachedPublish, run.state(),
					reachedPublish ? failures : List.of(), run.usage(), Duration.between(started, clock.instant()), toolCalls,
					note);
		});
	}

	private static String describe(CommandResult result) {
		return "`" + result.command() + "` " + (result.timedOut() ? "timed out" : "exit " + result.exitCode());
	}
}
