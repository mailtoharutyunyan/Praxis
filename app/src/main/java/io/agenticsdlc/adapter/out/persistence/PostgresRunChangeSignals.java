package io.agenticsdlc.adapter.out.persistence;

import io.agenticsdlc.core.port.RunChangeSignals;
import io.r2dbc.postgresql.api.Notification;
import io.r2dbc.postgresql.api.PostgresqlConnection;
import io.r2dbc.postgresql.api.PostgresqlResult;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

/**
 * Fans out Postgres {@code NOTIFY run_events} messages, so a live follower sees events written by any instance
 * within milliseconds. Uses one dedicated connection taken from the underlying (unpooled) factory and reconnects
 * with backoff. Notifications are only a hint: followers also poll, so a lost one costs latency, not correctness.
 */
@Component
class PostgresRunChangeSignals implements RunChangeSignals, SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(PostgresRunChangeSignals.class);

	private final ConnectionFactory connectionFactory;
	private final Sinks.Many<UUID> sink = Sinks.many().multicast().directBestEffort();
	private volatile Disposable subscription;

	PostgresRunChangeSignals(ConnectionFactory connectionFactory) {
		this.connectionFactory = unwrap(connectionFactory);
	}

	@Override
	public Flux<UUID> changes() {
		return sink.asFlux();
	}

	@Override
	public void start() {
		subscription = Flux.usingWhen(Mono.<Connection>from(connectionFactory.create()), this::listen, Connection::close)
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(30))
						.doBeforeRetry(signal -> log.warn("LISTEN {} connection lost, reconnecting (attempt {})",
								R2dbcRunStore.CHANNEL, signal.totalRetries() + 1, signal.failure())))
				.subscribe(this::publish, e -> log.error("LISTEN {} stopped", R2dbcRunStore.CHANNEL, e));
	}

	@Override
	public void stop() {
		Disposable current = subscription;
		if (current != null) {
			current.dispose();
		}
		subscription = null;
	}

	@Override
	public boolean isRunning() {
		return subscription != null && !subscription.isDisposed();
	}

	private Flux<Notification> listen(Connection connection) {
		if (!(connection instanceof PostgresqlConnection pg)) {
			return Flux.error(new IllegalStateException("LISTEN/NOTIFY needs a PostgreSQL connection, got "
					+ connection.getClass().getName()));
		}
		return pg.createStatement("LISTEN " + R2dbcRunStore.CHANNEL)
				.execute()
				.flatMap(PostgresqlResult::getRowsUpdated)
				.doOnComplete(() -> log.info("listening on {}", R2dbcRunStore.CHANNEL))
				.thenMany(pg.getNotifications())
				// The stream completes when the connection closes; make that a retryable error.
				.concatWith(Mono.error(() -> new IllegalStateException("notification stream ended")));
	}

	private void publish(Notification notification) {
		String payload = notification.getParameter();
		if (payload == null) {
			return;
		}
		try {
			sink.tryEmitNext(UUID.fromString(payload));
		}
		catch (IllegalArgumentException e) {
			log.warn("ignoring malformed {} payload", R2dbcRunStore.CHANNEL);
		}
	}

	/** The application's factory is a pool; LISTEN needs a connection the pool will never recycle. */
	private static ConnectionFactory unwrap(ConnectionFactory factory) {
		ConnectionFactory current = factory;
		while (current instanceof Wrapped<?> wrapped && wrapped.unwrap() instanceof ConnectionFactory inner
				&& inner != current) {
			current = inner;
		}
		return current;
	}
}
