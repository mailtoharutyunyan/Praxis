package io.agenticsdlc.core.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A request to change an open pull request: a reviewer's comment, a CI failure, or an operator's instruction.
 *
 * @param source where it came from: {@code api}, {@code mcp}, {@code ui}, {@code github}, {@code gitlab},
 * {@code bitbucket}, {@code azure-devops}, {@code ci}
 * @param sourceId unique per request at its source (e.g. {@code github:comment:123}); a repeated delivery is ignored
 * @param author who asked, as named by the source
 * @param text what should change; untrusted when it comes from outside the API
 * @param location optional file and line the comment refers to, e.g. {@code src/App.java:42}
 * @param url optional link back to the comment, pipeline or job
 */
public record RevisionRequest(String source, String sourceId, String author, String text, String location, String url) {

	public static final int MAX_TEXT_CHARS = 20_000;

	public RevisionRequest {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(sourceId, "sourceId");
		Objects.requireNonNull(author, "author");
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("a revision request needs text");
		}
		text = text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS) + "\n…";
	}

	Map<String, Object> toPayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("source", source);
		payload.put("sourceId", sourceId);
		payload.put("author", author);
		payload.put("text", text);
		payload.put("location", location);
		payload.put("url", url);
		return payload;
	}
}
