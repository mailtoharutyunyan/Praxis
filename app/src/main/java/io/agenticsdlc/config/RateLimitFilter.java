package io.agenticsdlc.config;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Token-bucket rate limits per client address and category, held in memory on each instance (see
 * {@link AgenticProperties.RateLimit}). Runs before authentication, so password guessing and floods are cut off
 * before any work is done. Behind a proxy, set {@code server.forward-headers-strategy} so the client address is real.
 */
final class RateLimitFilter implements WebFilter, Ordered {

	private static final int MAX_BUCKETS = 50_000;

	private enum Category {
		AUTH, WEBHOOK, API, NONE
	}

	/** Tokens refill continuously at {@code perMinute / 60} per second up to {@code perMinute}. */
	private static final class Bucket {
		private double tokens;
		private long updatedMillis;

		Bucket(double tokens, long now) {
			this.tokens = tokens;
			this.updatedMillis = now;
		}
	}

	private final AgenticProperties.RateLimit limits;
	private final Clock clock;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	RateLimitFilter(AgenticProperties.RateLimit limits, Clock clock) {
		this.limits = limits;
		this.clock = clock;
	}

	@Override
	public int getOrder() {
		// Ahead of the security filter chain (-100).
		return -200;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		Category category = category(exchange);
		if (!limits.enabled() || category == Category.NONE) {
			return chain.filter(exchange);
		}
		int perMinute = switch (category) {
			case AUTH -> limits.authPerMinute();
			case WEBHOOK -> limits.webhookPerMinute();
			default -> limits.apiPerMinute();
		};
		long retryAfter = take(category + "|" + client(exchange), perMinute);
		if (retryAfter == 0) {
			return chain.filter(exchange);
		}
		var response = exchange.getResponse();
		response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
		response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
		response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		byte[] body = ("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
				+ "\"detail\":\"Too many requests; retry in " + retryAfter + " s.\"}").getBytes();
		return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
	}

	private static Category category(ServerWebExchange exchange) {
		String path = exchange.getRequest().getPath().value();
		boolean post = exchange.getRequest().getMethod() == HttpMethod.POST;
		if (post && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/setup/admin")
				|| path.equals("/api/v1/auth/password"))) {
			return Category.AUTH;
		}
		if (path.startsWith("/api/v1/webhooks/")) {
			return Category.WEBHOOK;
		}
		return path.startsWith("/api/") || path.startsWith("/mcp") ? Category.API : Category.NONE;
	}

	private static String client(ServerWebExchange exchange) {
		InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
		return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
	}

	/** Takes one token; returns 0 when allowed, else the seconds until one is available. */
	long take(String key, int perMinute) {
		long now = clock.millis();
		if (buckets.size() > MAX_BUCKETS) {
			// Idle buckets are full again anyway; dropping them loses nothing.
			buckets.entrySet().removeIf(e -> now - e.getValue().updatedMillis > 60_000);
		}
		Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(perMinute, now));
		synchronized (bucket) {
			double perMilli = perMinute / 60_000.0;
			bucket.tokens = Math.min(perMinute, bucket.tokens + (now - bucket.updatedMillis) * perMilli);
			bucket.updatedMillis = now;
			if (bucket.tokens >= 1) {
				bucket.tokens -= 1;
				return 0;
			}
			return Math.max(1, (long) Math.ceil((1 - bucket.tokens) / perMilli / 1000));
		}
	}
}
