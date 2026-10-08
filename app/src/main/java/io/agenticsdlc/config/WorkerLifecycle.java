package io.agenticsdlc.config;

import io.agenticsdlc.core.engine.RunWorker;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Drives the {@link RunWorker}: {@code concurrency} independent loops, each claiming runs back to back and sleeping
 * {@code pollInterval} when nothing is claimable. On shutdown the loops stop claiming; a step still in flight is
 * cancelled and its lease simply expires, so another instance resumes the run (steps are idempotent).
 */
class WorkerLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(WorkerLifecycle.class);

	private final RunWorker worker;
	private final AgenticProperties.Worker settings;
	private volatile Disposable loops;

	WorkerLifecycle(RunWorker worker, AgenticProperties.Worker settings) {
		this.worker = worker;
		this.settings = settings;
	}

	@Override
	public void start() {
		log.info("starting run worker {} with {} loop(s)", worker.owner(), settings.concurrency());
		loops = Flux.range(0, settings.concurrency())
				.flatMap(i -> loop(), settings.concurrency())
				.subscribe();
	}

	private Mono<Void> loop() {
		Duration idle = settings.pollInterval();
		return Mono.defer(worker::processNext)
				.onErrorResume(e -> {
					log.warn("worker iteration failed", e);
					return Mono.just(false);
				})
				.flatMap(processed -> processed ? Mono.empty() : Mono.delay(idle).then())
				.repeat()
				.then();
	}

	@Override
	public void stop() {
		Disposable current = loops;
		if (current != null) {
			log.info("stopping run worker {}", worker.owner());
			current.dispose();
		}
		loops = null;
	}

	@Override
	public boolean isRunning() {
		return loops != null && !loops.isDisposed();
	}

	/**
	 * Highest phase: starts after the web server and stops before it, so no new claims are made while the
	 * application shuts down.
	 */
	@Override
	public int getPhase() {
		return SmartLifecycle.DEFAULT_PHASE;
	}
}
