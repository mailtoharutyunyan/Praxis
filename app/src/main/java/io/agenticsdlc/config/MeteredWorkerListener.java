package io.agenticsdlc.config;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.engine.WorkerListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics and logs for the worker:
 * {@code agentic.stage.duration{stage,next}}, {@code agentic.run.transitions{from,to}},
 * {@code agentic.stage.failures{stage}}, {@code agentic.step.abandoned{stage,reason}}.
 */
class MeteredWorkerListener implements WorkerListener {

	private static final Logger log = LoggerFactory.getLogger(MeteredWorkerListener.class);

	private final MeterRegistry meters;

	MeteredWorkerListener(MeterRegistry meters) {
		this.meters = meters;
	}

	@Override
	public void stepCompleted(Run before, Run after, Duration took) {
		Timer.builder("agentic.stage.duration")
				.tag("stage", before.state().name())
				.tag("next", after.state().name())
				.register(meters)
				.record(took);
		meters.counter("agentic.run.transitions", "from", before.state().name(), "to", after.state().name())
				.increment();
		log.info("run {} {} -> {} in {} ms", after.id(), before.state(), after.state(), took.toMillis());
	}

	@Override
	public void stageFailed(Run run, Throwable cause) {
		meters.counter("agentic.stage.failures", "stage", run.state().name()).increment();
		log.warn("run {} stage {} failed, escalating to a human", run.id(), run.state(), cause);
	}

	@Override
	public void stepAbandoned(UUID runId, RunState stage, Throwable cause) {
		meters.counter("agentic.step.abandoned", "stage", stage.name(), "reason", cause.getClass().getSimpleName())
				.increment();
		log.info("run {} step {} abandoned: {}", runId, stage, cause.toString());
	}
}
