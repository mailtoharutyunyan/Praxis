package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.SecurityScanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * VERIFYING: deterministic build and test in the sandbox, then security scans of the changed files. Pass moves on;
 * failing tests or a blocking finding (e.g. a committed secret) send the run back to implementation with the details
 * (bounded by the fix-iteration limit in {@code Transitions}). Other findings go to the reviewer and the gates.
 */
public final class VerifyStage implements StageHandler {

	/** Failure output passed back to the coder; the full output is in the event log. */
	static final int FAILURE_TAIL_CHARS = 6_000;

	public static final String SCAN = "scan";

	private final RunWorkspace workspace;
	private final SecurityScanner scanner;

	public VerifyStage(RunWorkspace workspace) {
		this(workspace, (runId, files) -> Mono.just(SecurityScanner.Report.EMPTY));
	}

	public VerifyStage(RunWorkspace workspace, SecurityScanner scanner) {
		this.workspace = Objects.requireNonNull(workspace, "workspace");
		this.scanner = Objects.requireNonNull(scanner, "scanner");
	}

	@Override
	public RunState stage() {
		return RunState.VERIFYING;
	}

	@Override
	public Mono<StageOutcome> execute(StageContext context) {
		// Only the services the change touches are built and tested (ADR-0006).
		return workspace.prepare(context)
				.flatMap(prepared -> workspace.diff(context).flatMap(diff -> workspace
						.verify(context, prepared, TestPaths.changedFiles(diff))
						.flatMap(results -> outcome(context, diff, results))))
				.onErrorResume(RunWorkspace.UndetectableBuildException.class,
						e -> Mono.just(new StageOutcome.Escalate(e.getMessage(), Usage.ZERO)));
	}

	private Mono<StageOutcome> outcome(StageContext context, String diff, List<CommandResult> results) {
		return Mono.just(results)
				.flatMap(ran -> {
					CommandResult last = ran.getLast();
					if (last.succeeded()) {
						return scan(context, diff, ran.size());
					}
					String why = last.timedOut() ? "timed out" : "exited with " + last.exitCode();
					return Mono.just((StageOutcome) new StageOutcome.NeedsRework("`" + last.command() + "` " + why
							+ ":\n" + last.tail(FAILURE_TAIL_CHARS), Usage.ZERO));
				});
	}

	private Mono<StageOutcome> scan(StageContext context, String diff, int commands) {
		List<String> changed = TestPaths.changedFiles(diff);
		return scanner.scan(context.run().id(), changed)
				.onErrorResume(e -> Mono.just(new SecurityScanner.Report(List.of(),
						List.of("Security scan could not run: " + e.getMessage()))))
				.map(report -> {
					String contracts = ContractFiles.note(changed);
					if (contracts == null) {
						return report;
					}
					List<String> notes = new java.util.ArrayList<>(report.notes());
					notes.add(contracts);
					return new SecurityScanner.Report(report.findings(), notes);
				})
				.flatMap(report -> {
					Map<String, Object> scan = new LinkedHashMap<>();
					scan.put("kind", SCAN);
					scan.put("findings", report.findings().size());
					scan.put("blocking", report.blocking().size());
					scan.put("contracts", ContractFiles.changed(changed));
					scan.put("content", report.summary());
					Mono<Void> recorded = context.emit(RunEventType.ARTIFACT_PRODUCED, "system", scan);
					if (!report.blocking().isEmpty()) {
						return recorded.thenReturn((StageOutcome) new StageOutcome.NeedsRework("Security scan found "
								+ report.blocking().size() + " problem(s) that must not be published; fix them (never "
								+ "commit secrets: read them from configuration or the environment):\n" + report.summary(),
								Usage.ZERO));
					}
					// The verified diff is what the IMPLEMENTATION gate shows the approver.
					return recorded.then(context.emit(RunEventType.ARTIFACT_PRODUCED, "system",
									Map.of("kind", RunHistory.DIFF, RunHistory.FINGERPRINT, RunHistory.fingerprint(diff),
											"content", diff)))
							.thenReturn((StageOutcome) new StageOutcome.Completed(Usage.ZERO,
									Map.of("commands", commands, "result", "PASSED", "findings", report.findings().size())));
				});
	}
}
