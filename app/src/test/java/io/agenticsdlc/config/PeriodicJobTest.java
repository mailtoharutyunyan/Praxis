package io.agenticsdlc.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class PeriodicJobTest {

	@Test
	void aFailingOrHangingSweepNeverStopsTheSchedule() throws Exception {
		AtomicInteger sweeps = new AtomicInteger();
		PeriodicJob job = new PeriodicJob("test", Duration.ZERO, Duration.ofMillis(20), Duration.ofMillis(50), () -> {
			int n = sweeps.incrementAndGet();
			return n == 1 ? Flux.error(new IllegalStateException("boom")) : n == 2 ? Mono.never() : Mono.empty();
		}, null);
		job.start();
		try {
			long deadline = System.currentTimeMillis() + 5_000;
			while (sweeps.get() < 4 && System.currentTimeMillis() < deadline) {
				Thread.sleep(10);
			}
			assertThat(sweeps.get()).isGreaterThanOrEqualTo(4);
			assertThat(job.isRunning()).isTrue();
		}
		finally {
			job.stop();
		}
		assertThat(job.isRunning()).isFalse();
	}

	@Test
	void onlyTheLeaseHolderSweeps() {
		AtomicInteger sweeps = new AtomicInteger();
		AtomicInteger asked = new AtomicInteger();
		PeriodicJob.Lease lease = ttl -> {
			assertThat(ttl).isEqualTo(Duration.ofSeconds(70));
			return asked.incrementAndGet() == 1 ? Mono.just(false) : Mono.error(new IllegalStateException("db down"));
		};
		PeriodicJob job = new PeriodicJob("test", Duration.ofSeconds(10), Duration.ofMinutes(1),
				() -> Mono.fromRunnable(sweeps::incrementAndGet), lease);

		job.runOnce().block();
		job.runOnce().block();

		assertThat(asked).hasValue(2);
		assertThat(sweeps).hasValue(0);
	}
}
