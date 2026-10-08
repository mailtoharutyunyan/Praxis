package io.agenticsdlc.config;

import io.agenticsdlc.core.engine.RunWorker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Drives the {@link RunWorker}: {@code concurrency} independent loops, each claiming runs back to back and sleeping
 * {@code pollInterval} when nothing is claimable. On shutdown the loops stop claiming and steps in flight get
 * {@code drainTimeout} to finish; the rest are cancelled and release their lease, so another instance resumes the run
 * at once (steps are idempotent).
 */
class WorkerLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(WorkerLifecycle.class);

	private final RunWorker worker;
	private final AgenticProperties.Worker settings;
	private volatile Disposable loops;
	private volatile boolean draining;
	private volatile Sinks.Empty<Void> stopped;

	WorkerLifecycle(RunWorker worker, AgenticProperties.Worker settings) {
		this.worker = worker;
		this.settings = settings;
	}

	@Override
	public void start() {
		log.info("starting run worker {} with {} loop(s)", worker.owner(), settings.concurrency());
		draining = false;
		Sinks.Empty<Void> done = Sinks.empty();
		stopped = done;
		loops = Flux.range(0, settings.concurrency())
				.flatMap(i -> loop(), settings.concurrency())
				.doFinally(signal -> done.tryEmitEmpty())
				.subscribe();
	}

	private Mono<Void> loop() {
		Duration idle = settings.pollInterval();
		return Mono.defer(worker::processNext)
				.onErrorResume(e -> {
					log.warn("worker iteration failed", e);
					return Mono.just(false);
				})
				.flatMap(processed -> processed || draining ? Mono.empty() : Mono.delay(idle).then())
				.repeat(() -> !draining)
				.then();
	}

	/** Stop claiming, wait up to {@code drainTimeout} for steps in flight, then cancel what is left. */
	@Override
	public void stop(Runnable callback) {
		Disposable current = loops;
		if (current == null) {
			callback.run();
			return;
		}
		log.info("draining run worker {} (up to {})", worker.owner(), settings.drainTimeout());
		draining = true;
		AtomicBoolean finished = new AtomicBoolean();
		Runnable finish = () -> {
			if (finished.compareAndSet(false, true)) {
				loops = null;
				callback.run();
			}
		};
		Mono.firstWithSignal(stopped.asMono(), Mono.delay(settings.drainTimeout()).then(Mono.fromRunnable(() -> {
			log.warn("run worker {} still busy after {}; cancelling and handing its runs over", worker.owner(),
					settings.drainTimeout());
			current.dispose();
		})))
				.doFinally(signal -> finish.run())
				.subscribe(ignored -> {
				}, e -> finish.run());
	}

	@Override
	public void stop() {
		Disposable current = loops;
		if (current != null) {
			log.info("stopping run worker {}", worker.owner());
			draining = true;
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
