package io.agenticsdlc.core.eval;

import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of one attempt at a case.
 *
 * @param reachedPublish the pipeline finished (review approved) and waited for publishing
 * @param failedChecks grading commands that did not pass, with their exit codes
 * @param note why the trial failed before grading (escalation reason, timeout), else null
 */
public record EvalTrial(String caseId, int trial, UUID runId, boolean passed, boolean reachedPublish,
		RunState finalState, List<String> failedChecks, Usage usage, Duration duration, int toolCalls, String note) {

	public EvalTrial {
		failedChecks = List.copyOf(failedChecks);
	}
}
