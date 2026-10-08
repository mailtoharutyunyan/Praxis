package io.agenticsdlc.adapter.out.scm;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;

/**
 * Shared HTTP plumbing for the SCM providers: one {@link WebClient}, per-host tokens and API base overrides, JSON
 * in and out, retries for rate limits and server errors, and errors that never include the token.
 */
public class ScmHttp {

	private final WebClient client;
	private final Map<String, String> tokensByHost;
	private final Map<String, String> apiUrlsByHost;
	private final Duration timeout;

	public ScmHttp(WebClient.Builder builder, Map<String, String> tokensByHost, Map<String, String> apiUrlsByHost,
			Duration timeout) {
		this.client = builder.build();
		this.tokensByHost = Map.copyOf(tokensByHost);
		this.apiUrlsByHost = Map.copyOf(apiUrlsByHost);
		this.timeout = timeout;
	}

	/** The configured API base for a host, else the provider default. Never ends with a slash. */
	String apiBase(String host, String defaultBase) {
		String base = apiUrlsByHost.getOrDefault(host, defaultBase);
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	String token(String host) {
		String token = tokensByHost.get(host);
		if (token == null || token.isBlank()) {
			throw new IllegalStateException("no SCM token configured for " + host + " (agentic.scm.tokens.[" + host + "])");
		}
		return token;
	}

	/** Reads are retried on transient failures. */
	Mono<JsonNode> get(String url, Consumer<HttpHeaders> headers) {
		return exchange(client.get().uri(java.net.URI.create(url)).headers(headers).accept(MediaType.APPLICATION_JSON)
				.retrieve().bodyToMono(JsonNode.class)
				.timeout(timeout)
				.retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).filter(ScmHttp::transientFailure)));
	}

	/**
	 * Writes are never retried here: a POST that timed out may have succeeded. Callers retry the whole operation,
	 * starting with a lookup of what already exists (see {@link ScmPullRequests}).
	 */
	Mono<JsonNode> post(String url, Consumer<HttpHeaders> headers, Object body) {
		return exchange(client.post().uri(java.net.URI.create(url)).headers(headers).contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(JsonNode.class)
				.timeout(timeout));
	}

	/** Worth repeating a whole find-or-create operation: transient failures, and conflicts from a half-done create. */
	static boolean retryableOperation(Throwable error) {
		if (error instanceof ScmException e) {
			int status = e.status().value();
			return status == 409 || status == 422 || status == 429 || e.status().is5xxServerError();
		}
		return transientFailure(error);
	}

	/**
	 * The last {@code maxBytes} of a text resource such as a CI job log, decoded as UTF-8 and starting at a line
	 * boundary. Hosts answer log requests with a redirect to short-lived storage; it is followed once, without the
	 * token, which must never leave the code host.
	 */
	Mono<String> textTail(String url, Consumer<HttpHeaders> headers, int maxBytes) {
		return exchange(client.get().uri(java.net.URI.create(url)).headers(headers).exchangeToMono(response -> {
			if (response.statusCode().is3xxRedirection() && response.headers().asHttpHeaders().getLocation() != null) {
				java.net.URI location = java.net.URI.create(url).resolve(response.headers().asHttpHeaders().getLocation());
				return response.releaseBody().then(client.get().uri(location).exchangeToMono(r -> tail(r, maxBytes)));
			}
			return tail(response, maxBytes);
		}).timeout(timeout));
	}

	private static Mono<String> tail(org.springframework.web.reactive.function.client.ClientResponse response, int maxBytes) {
		if (response.statusCode().isError()) {
			return response.createException().flatMap(Mono::error);
		}
		return response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
				.reduce(new java.io.ByteArrayOutputStream(), (out, buffer) -> {
					try {
						byte[] bytes = new byte[buffer.readableByteCount()];
						buffer.read(bytes);
						out.writeBytes(bytes);
						if (out.size() > 2 * maxBytes) {
							byte[] all = out.toByteArray();
							out.reset();
							out.write(all, all.length - maxBytes, maxBytes);
						}
						return out;
					}
					finally {
						org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
					}
				})
				.map(out -> {
					byte[] all = out.toByteArray();
					boolean cut = all.length > maxBytes;
					String text = new String(all, cut ? all.length - maxBytes : 0, Math.min(all.length, maxBytes),
							java.nio.charset.StandardCharsets.UTF_8);
					int newline = text.indexOf('\n');
					return cut && newline >= 0 ? text.substring(newline + 1) : text;
				})
				.defaultIfEmpty("");
	}

	private <T> Mono<T> exchange(Mono<T> call) {
		return call
				.onErrorMap(WebClientResponseException.class, e -> new ScmException(e.getStatusCode(),
						e.getRequest() == null ? "" : e.getRequest().getMethod() + " " + e.getRequest().getURI().getPath(),
						e.getResponseBodyAsString()));
	}

	private static boolean transientFailure(Throwable error) {
		if (error instanceof WebClientResponseException e) {
			HttpStatusCode status = e.getStatusCode();
			return status.value() == 429 || status.is5xxServerError();
		}
		return error instanceof java.util.concurrent.TimeoutException || error instanceof java.io.IOException;
	}

	/** A provider rejected a call. The message carries status and path, never credentials. */
	public static final class ScmException extends RuntimeException {
		private final HttpStatusCode status;

		ScmException(HttpStatusCode status, String request, String body) {
			super("SCM API " + request + " failed with " + status.value() + ": "
					+ (body.length() <= 500 ? body : body.substring(0, 500) + "…"));
			this.status = status;
		}

		public HttpStatusCode status() {
			return status;
		}
	}
}
