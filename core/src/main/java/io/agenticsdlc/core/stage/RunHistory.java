package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reads what earlier stages and humans left in the event log: artifacts, change requests, failures. */
public record RunHistory(List<RunEvent> events) {

	public static final String SPEC = "spec";
	public static final String DIFF = "diff";
	public static final String REVIEW = "review";

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
