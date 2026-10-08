package io.agenticsdlc.core.workspace;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Deterministic security checks on a run's working copy before its changes can be published: secrets and
 * dependencies with known vulnerabilities. Complements the model reviewer, which misses whole classes of problems.
 */
public interface SecurityScanner {

	/**
	 * @param changedFiles files the run added or changed (relative paths); findings elsewhere were there before the
	 *        run and are not reported
	 */
	Mono<Report> scan(UUID runId, List<String> changedFiles);

	/**
	 * @param severity CRITICAL, HIGH, MEDIUM or LOW
	 * @param blocking the change must not be published with it (e.g. a committed secret)
	 */
	record Finding(String tool, String severity, String file, int line, String rule, String message, boolean blocking) {
		public Finding {
			Objects.requireNonNull(tool, "tool");
			Objects.requireNonNull(severity, "severity");
			Objects.requireNonNull(file, "file");
		}

		String describe() {
			return "[" + severity + "] " + file + (line > 0 ? ":" + line : "") + " " + message + " (" + tool + " "
					+ rule + ")";
		}
	}

	/** @param notes what was skipped or could not run, for the record */
	record Report(List<Finding> findings, List<String> notes) {
		public static final Report EMPTY = new Report(List.of(), List.of());

		public Report {
			findings = List.copyOf(findings);
			notes = List.copyOf(notes);
		}

		public List<Finding> blocking() {
			return findings.stream().filter(Finding::blocking).toList();
		}

		/** Markdown summary for the reviewer, the gate and the pull request. */
		public String summary() {
			StringBuilder text = new StringBuilder();
			if (findings.isEmpty()) {
				text.append("No findings in the changed files.");
			}
			findings.forEach(f -> text.append("- ").append(f.describe()).append('\n'));
			notes.forEach(n -> text.append("\n_").append(n).append("_"));
			return text.toString().strip();
		}
	}
}
