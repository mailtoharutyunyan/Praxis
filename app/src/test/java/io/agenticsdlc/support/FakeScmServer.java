package io.agenticsdlc.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Minimal in-process HTTP server: routes "METHOD path-prefix" to JSON responders, records every request. */
public final class FakeScmServer implements AutoCloseable {

	public record Request(String method, String uri, Map<String, List<String>> headers, String body) {
		public String header(String name) {
			return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().getFirst())
					.findFirst().orElse(null);
		}
	}

	/** @param location for redirects; null otherwise */
	public record Response(int status, String json, String location) {
		public Response(int status, String json) {
			this(status, json, null);
		}
	}

	private final HttpServer server;
	private final Map<String, Function<Request, Response>> routes = new ConcurrentHashMap<>();
	public final List<Request> requests = java.util.Collections.synchronizedList(new ArrayList<>());

	public FakeScmServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	public String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public FakeScmServer on(String method, String pathPrefix, Function<Request, Response> responder) {
		routes.put(method + " " + pathPrefix, responder);
		return this;
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
				exchange.getRequestHeaders(), body);
		requests.add(request);
		String path = exchange.getRequestURI().getRawPath();
		Response response = routes.entrySet().stream()
				.filter(e -> e.getKey().startsWith(request.method() + " ") && path.startsWith(e.getKey().substring(
						request.method().length() + 1)))
				.max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
				.map(e -> e.getValue().apply(request))
				.orElse(new Response(404, "{\"message\":\"no route for " + request.method() + " " + path + "\"}"));
		byte[] bytes = response.json().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		if (response.location() != null) {
			exchange.getResponseHeaders().add("Location", response.location());
		}
		exchange.sendResponseHeaders(response.status(), bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
