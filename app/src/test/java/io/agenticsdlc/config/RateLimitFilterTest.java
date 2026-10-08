package io.agenticsdlc.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RateLimitFilterTest {

	private final AtomicLong millis = new AtomicLong(1_000_000);
	private final Clock clock = new Clock() {
		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(millis.get());
		}
	};

	@Test
	void signInIsLimitedPerAddressAndRefillsOverTime() {
		RateLimitFilter filter = new RateLimitFilter(new AgenticProperties.RateLimit(true, 3, 100, 100), clock);
		for (int i = 0; i < 3; i++) {
			assertThat(status(filter, "/api/v1/auth/login", "10.0.0.1")).isNull();
		}
		MockServerWebExchange limited = exchange("/api/v1/auth/login", "10.0.0.1");
		filter.filter(limited, e -> Mono.empty()).block();
		assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(limited.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("20");

		// Another address has its own budget, and the UI's static files are never limited.
		assertThat(status(filter, "/api/v1/auth/login", "10.0.0.2")).isNull();
		for (int i = 0; i < 10; i++) {
			assertThat(status(filter, "/assets/main.js", "10.0.0.1")).isNull();
		}
		millis.addAndGet(20_000);
		assertThat(status(filter, "/api/v1/auth/login", "10.0.0.1")).isNull();
	}

	@Test
	void disabledLimitsLetEverythingThrough() {
		RateLimitFilter filter = new RateLimitFilter(new AgenticProperties.RateLimit(false, 1, 1, 1), clock);
		for (int i = 0; i < 5; i++) {
			assertThat(status(filter, "/api/v1/runs", "10.0.0.1")).isNull();
		}
	}

	private static MockServerWebExchange exchange(String path, String address) {
		return MockServerWebExchange.from(MockServerHttpRequest.post(path)
				.remoteAddress(new java.net.InetSocketAddress(address, 40000)));
	}

	private static HttpStatus status(RateLimitFilter filter, String path, String address) {
		MockServerWebExchange exchange = exchange(path, address);
		filter.filter(exchange, e -> Mono.empty()).block();
		return (HttpStatus) exchange.getResponse().getStatusCode();
	}
}
