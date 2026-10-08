package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.workspace.CommandResult;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * VERIFYING: deterministic build and test in the sandbox. Pass moves on; failure sends the run back to
 * implementation with the failing output (bounded by the fix-iteration limit in {@code Transitions}).
 */
public final class VerifyStage implements StageHandler {

	/** Failure output passed back to the coder; the full output is in the event log. */
	static final int FAILURE_TAIL_CHARS = 6_000;

	private final RunWorkspace workspace;

	public VerifyStage(RunWorkspace workspace) {
		this.workspace = Objects.requireNonNull(workspace, "workspace");
	}

	@Override
	public RunState stage() {
		return RunState.VERIFYING;
	}

	@Override
	public Mono<StageOutcome> execute(StageContext context) {
		return workspace.prepare(context)
				.flatMap(prepared -> workspace.runAll(context, prepared.profile().verifyCommands()))
				.flatMap(results -> {
					CommandResult last = results.getLast();
					if (last.succeeded()) {
						// The verified diff is what the IMPLEMENTATION gate shows the approver.
						return workspace.diff(context)
								.flatMap(diff -> context.emit(RunEventType.ARTIFACT_PRODUCED, "system",
										Map.of("kind", RunHistory.DIFF, RunHistory.FINGERPRINT, RunHistory.fingerprint(diff),
												"content", diff)))
								.thenReturn((StageOutcome) new StageOutcome.Completed(Usage.ZERO,
										Map.of("commands", results.size(), "result", "PASSED")));
					}
					String why = last.timedOut() ? "timed out" : "exited with " + last.exitCode();
					return Mono.just((StageOutcome) new StageOutcome.NeedsRework("`" + last.command() + "` " + why
							+ ":\n" + last.tail(FAILURE_TAIL_CHARS), Usage.ZERO));
				})
				.onErrorResume(RunWorkspace.UndetectableBuildException.class,
						e -> Mono.just(new StageOutcome.Escalate(e.getMessage(), Usage.ZERO)));
	}
}
