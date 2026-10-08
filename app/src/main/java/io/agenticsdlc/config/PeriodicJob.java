package io.agenticsdlc.config;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs a background sweep on a fixed interval and keeps running whatever happens to one sweep: ticks that arrive while
 * a sweep is still busy are dropped (never overflowing the schedule), a failed sweep is logged, and a hung one is cut
 * off after {@code timeout}. With a lease, only the instance holding it sweeps, for jobs that must not run twice in a
 * cluster; the lease outlives one interval plus the timeout, so it stays with the same instance while that one lives.
 */
final class PeriodicJob implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(PeriodicJob.class);

	/** Acquires or renews a cluster-wide lease on a job for the given time; emits false if another instance holds it. */
	@FunctionalInterface
	interface Lease extends Function<Duration, Mono<Boolean>> {
	}

	private final String name;
	private final Duration interval;
	private final Duration timeout;
	private final Supplier<? extends Publisher<?>> sweep;
	private final Lease lease;
	private volatile Disposable schedule;

	/** @param lease null to sweep on every instance (per-node work, such as cleaning local workspaces) */
	PeriodicJob(String name, Duration interval, Duration timeout, Supplier<? extends Publisher<?>> sweep, Lease lease) {
		this.name = Objects.requireNonNull(name, "name");
		this.interval = Objects.requireNonNull(interval, "interval");
		this.timeout = Objects.requireNonNull(timeout, "timeout");
		this.sweep = Objects.requireNonNull(sweep, "sweep");
		this.lease = lease;
		if (interval.isZero() || interval.isNegative()) {
			throw new IllegalArgumentException(name + ": interval must be positive");
		}
	}

	/** One sweep, if this instance may run it; never fails. */
	Mono<Void> runOnce() {
		Mono<Boolean> mayRun = lease == null ? Mono.just(true)
				: lease.apply(interval.plus(timeout)).onErrorResume(e -> {
					log.warn("{}: could not acquire its lease", name, e);
					return Mono.just(false);
				});
		return mayRun.filter(Boolean::booleanValue)
				.flatMap(go -> Flux.from(sweep.get()).then().timeout(timeout))
				.onErrorResume(e -> {
					log.warn("{} failed", name, e);
					return Mono.empty();
				});
	}

	@Override
	public void start() {
		schedule = Flux.interval(interval, interval)
				.onBackpressureDrop()
				.concatMap(tick -> runOnce(), 1)
				.subscribe();
	}

	@Override
	public void stop() {
		Disposable current = schedule;
		if (current != null) {
			current.dispose();
		}
		schedule = null;
	}

	@Override
	public boolean isRunning() {
		return schedule != null && !schedule.isDisposed();
	}
}
