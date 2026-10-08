package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.workspace.CommandResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * PREPARING_CONTEXT: clone the repository, detect the toolchain, start the sandbox, install dependencies and run a
 * baseline build. A failing baseline is recorded, not fatal: the task may be to fix exactly that.
 */
public final class PrepareContextStage implements StageHandler {

	private final RunWorkspace workspace;

	public PrepareContextStage(RunWorkspace workspace) {
		this.workspace = Objects.requireNonNull(workspace, "workspace");
	}

	@Override
	public RunState stage() {
		return RunState.PREPARING_CONTEXT;
	}

	@Override
	public Mono<StageOutcome> execute(StageContext context) {
		return workspace.prepare(context)
				.flatMap(prepared -> workspace.verifyPushAccess(context).thenReturn(prepared))
				.flatMap(prepared -> workspace.baseline(context, prepared).map(results -> {
					boolean passed = results.stream().allMatch(CommandResult::succeeded);
					Map<String, Object> summary = new LinkedHashMap<>();
					summary.put("services", prepared.plan().components().stream()
							.map(c -> c.name() + " (" + c.path() + ", " + c.profile().tool() + ")").toList());
					summary.put("tool", prepared.plan().main().profile().tool());
					summary.put("image", prepared.plan().main().profile().image());
					summary.put("sidecars", prepared.plan().sidecars().stream().map(s -> s.name()).toList());
					summary.put("baseBranch", prepared.checkout().baseBranch());
					summary.put("baseCommit", prepared.checkout().baseCommit());
					summary.put("workBranch", prepared.checkout().workBranch());
					summary.put("baselineBuild", passed ? "PASSED" : "FAILED");
					summary.put("agentInstructions", prepared.checkout().agentInstructions() != null);
					return (StageOutcome) new StageOutcome.Completed(Usage.ZERO, summary);
				}))
				.onErrorResume(RunWorkspace.UndetectableBuildException.class,
						e -> Mono.just(new StageOutcome.Escalate(e.getMessage(), Usage.ZERO)))
				// Before any model work: a read-only token would otherwise only fail at publishing.
				.onErrorResume(io.agenticsdlc.core.workspace.PushAccessDeniedException.class,
						e -> Mono.just(new StageOutcome.Escalate(e.getMessage(), Usage.ZERO)));
	}
}
