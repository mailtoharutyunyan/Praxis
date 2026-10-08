package io.agenticsdlc.core.engine;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunState;
import java.time.Duration;
import java.util.UUID;

/** Observes the worker for metrics and logs. Implementations must be fast and must not throw. */
public interface WorkerListener {

	WorkerListener NONE = new WorkerListener() {
	};

	/** A stage finished and its result was stored. */
	default void stepCompleted(Run before, Run after, Duration took) {
	}

	/** A stage threw or timed out; the run was escalated to a human. */
	default void stageFailed(Run run, Throwable cause) {
	}

	/** The result of a step was dropped: the lease was lost or the run changed underneath (e.g. cancelled). */
	default void stepAbandoned(UUID runId, RunState stage, Throwable cause) {
	}
}
