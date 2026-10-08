package io.agenticsdlc.adapter.in.web;

import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Ends live SSE streams when shutdown begins, before the web server's graceful shutdown waits for open requests.
 * Without this a long-lived stream holds every deploy for the full shutdown timeout. Clients reconnect with
 * {@code Last-Event-ID} to another instance and resume without gaps.
 */
@Component
class EventStreamShutdown implements SmartLifecycle {

	private final Sinks.Empty<Void> stopping = Sinks.empty();
	private volatile boolean running;

	/** Completes when the application starts shutting down. */
	Mono<Void> signal() {
		return stopping.asMono();
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		stopping.tryEmitEmpty();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** Higher phase stops earlier: just before graceful shutdown starts waiting for active requests. */
	@Override
	public int getPhase() {
		return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
	}
}
