package io.agenticsdlc.config;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/** Deletes finished runs and dead API tokens past their retention, from one instance at a time. */
@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {

	private static final Logger log = LoggerFactory.getLogger(RetentionConfiguration.class);
	private static final int BATCH = 500;

	@Bean
	PeriodicJob retentionJob(Housekeeping housekeeping, AgenticProperties properties, Clock clock,
			ClusterConfiguration.JobLeases leases) {
		AgenticProperties.Retention retention = properties.retention();
		return new PeriodicJob("data retention", Duration.ofMinutes(5), retention.interval(), Duration.ofMinutes(30),
				() -> runs(housekeeping, retention, clock).then(housekeeping.deleteDeadTokens(
						clock.instant().minus(retention.revokedTokens())))
						.doOnNext(n -> {
							if (n > 0) {
								log.info("deleted {} revoked or expired API tokens", n);
							}
						}),
				leases.forJob("data-retention"));
	}

	/** Batch after batch until a batch comes back short, so a large backlog never holds one long transaction. */
	private static Mono<Long> runs(Housekeeping housekeeping, AgenticProperties.Retention retention, Clock clock) {
		if (retention.finishedRuns().isZero()) {
			return Mono.just(0L);
		}
		return Mono.defer(() -> housekeeping.deleteFinishedRuns(clock.instant().minus(retention.finishedRuns()), BATCH))
				.expand(n -> n < BATCH ? Mono.empty()
						: housekeeping.deleteFinishedRuns(clock.instant().minus(retention.finishedRuns()), BATCH))
				.reduce(0L, Long::sum)
				.doOnNext(n -> {
					if (n > 0) {
						log.info("deleted {} finished runs older than {}", n, retention.finishedRuns());
					}
				});
	}
}
