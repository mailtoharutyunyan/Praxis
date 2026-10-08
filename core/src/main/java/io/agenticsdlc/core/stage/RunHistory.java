package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reads what earlier stages and humans left in the event log: artifacts, change requests, failures. */
public record RunHistory(List<RunEvent> events) {

	public static final String SPEC = "spec";
	public static final String DIFF = "diff";
	public static final String REVIEW = "review";
	/** The critic's findings on a new specification; payload {@code verdict}: OK, REVISE or UNAVAILABLE. */
	public static final String SPEC_REVIEW = "spec-review";
	/** Tests written before the implementation: payload {@code files} (path to SHA-256), {@code failedFirst}. */
	public static final String TESTS = "tests";
	/** Payload key of a diff artifact's SHA-256, so publishing can prove it pushes exactly the approved diff. */
	public static final String FINGERPRINT = "sha256";

	public RunHistory {
		events = List.copyOf(events);
	}

	/** Most recent artifact of a kind ({@code ARTIFACT_PRODUCED} with {@code kind}). */
	public Optional<String> latestArtifact(String kind) {
		return events.reversed().stream()
				.filter(e -> e.type() == RunEventType.ARTIFACT_PRODUCED && kind.equals(e.payload().get("kind")))
				.map(e -> Objects.toString(e.payload().get("content"), ""))
				.findFirst();
	}

	/** The most recent artifact event of a kind, with its whole payload. */
	public Optional<RunEvent> latestArtifactEvent(String kind) {
		return events.reversed().stream()
				.filter(e -> e.type() == RunEventType.ARTIFACT_PRODUCED && kind.equals(e.payload().get("kind")))
				.findFirst();
	}

	/** Fingerprint of the most recent diff artifact, if it has one. */
	public Optional<String> latestDiffFingerprint() {
		return events.reversed().stream()
				.filter(e -> e.type() == RunEventType.ARTIFACT_PRODUCED && DIFF.equals(e.payload().get("kind")))
				.findFirst()
				.map(e -> e.payload().get(FINGERPRINT))
				.map(Object::toString);
	}

	public static String fingerprint(String content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A revision of the open pull request being worked on. */
	public record Revision(String source, String author, String text, String location, String url) {

		/** For the agents: who asked and where, then the request itself. */
		public String describe() {
			return "Requested via " + source + " by " + author + (location == null ? "" : " on " + location) + ":\n"
					+ text;
		}
	}

	/**
	 * The revision this round works on: the newest {@code REVISION_REQUESTED} since the pull request was last
	 * published. Empty for a run's first round.
	 */
	public Optional<Revision> currentRevision() {
		for (RunEvent event : events.reversed()) {
			if (event.type() == RunEventType.ARTIFACT_PRODUCED && "pull-request".equals(event.payload().get("kind"))) {
				return Optional.empty();
			}
			if (event.type() == RunEventType.REVISION_REQUESTED) {
				Map<String, Object> p = event.payload();
				return Optional.of(new Revision(String.valueOf(p.get("source")), String.valueOf(p.get("author")),
						Objects.toString(p.get("text"), ""), (String) p.get("location"), (String) p.get("url")));
			}
		}
		return Optional.empty();
	}

	/** The latest gate decision, if it asked for changes: who and what they wrote. */
	public Optional<String> latestChangeRequest() {
		return events.reversed().stream()
				.filter(e -> e.type() == RunEventType.GATE_DECIDED)
				.findFirst()
				.filter(e -> "REQUEST_CHANGES".equals(e.payload().get("decision")))
				.map(e -> e.actor() + " requested changes at the " + e.payload().get("gate") + " gate: "
						+ Objects.toString(e.payload().get("comment"), "(no comment)"));
	}

	/**
	 * Why the run is back in implementation: the most recent rework reason from verification or review, provided it
	 * is newer than the last time implementation completed.
	 */
	public Optional<String> latestRework() {
		for (RunEvent event : events.reversed()) {
			if (event.type() != RunEventType.STAGE_COMPLETED) {
				continue;
			}
			Object stage = event.payload().get("stage");
			if ("IMPLEMENTING".equals(stage)) {
				return Optional.empty();
			}
			if ("NeedsRework".equals(event.payload().get("outcome"))) {
				return Optional.of(stage + " found problems:\n" + Objects.toString(event.payload().get("detail"), ""));
			}
		}
		return Optional.empty();
	}
}
