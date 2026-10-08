package io.agenticsdlc.core.eval;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated results.
 * <ul>
 * <li>{@code passAt1}: share of trials that passed — the chance one attempt solves a case.</li>
 * <li>{@code passHatK}: share of cases where all k trials passed — consistency.</li>
 * </ul>
 */
public record EvalReport(String suite, int trialsPerCase, List<EvalTrial> trials) {

	public EvalReport {
		trials = List.copyOf(trials);
	}

	public double passAt1() {
		return trials.isEmpty() ? 0 : (double) trials.stream().filter(EvalTrial::passed).count() / trials.size();
	}

	public double passHatK() {
		Map<String, List<EvalTrial>> byCase = byCase();
		return byCase.isEmpty() ? 0
				: (double) byCase.values().stream().filter(t -> t.stream().allMatch(EvalTrial::passed)).count() / byCase.size();
	}

	public double meanCostUsd() {
		return trials.stream().mapToLong(t -> t.usage().costMicroUsd()).average().orElse(0) / 1_000_000.0;
	}

	public long meanTokens() {
		return Math.round(trials.stream().mapToLong(t -> t.usage().totalTokens()).average().orElse(0));
	}

	public Duration medianDuration() {
		List<Duration> sorted = trials.stream().map(EvalTrial::duration).sorted().toList();
		return sorted.isEmpty() ? Duration.ZERO : sorted.get(sorted.size() / 2);
	}

	public Map<String, List<EvalTrial>> byCase() {
		return trials.stream().sorted(Comparator.comparing(EvalTrial::caseId).thenComparing(EvalTrial::trial))
				.collect(Collectors.groupingBy(EvalTrial::caseId, LinkedHashMap::new, Collectors.toList()));
	}

	public String toMarkdown() {
		StringBuilder md = new StringBuilder();
		md.append("# Evaluation: ").append(suite).append("\n\n");
		md.append(String.format("| pass@1 | pass^%d | cases | trials | mean cost | mean tokens | median duration |%n",
				trialsPerCase));
		md.append("|---|---|---|---|---|---|---|\n");
		md.append(String.format("| %.0f%% | %.0f%% | %d | %d | $%.4f | %d | %ds |%n%n", passAt1() * 100, passHatK() * 100,
				byCase().size(), trials.size(), meanCostUsd(), meanTokens(), medianDuration().toSeconds()));
		md.append("| case | trial | result | final state | failed checks | cost | tool calls | note |\n");
		md.append("|---|---|---|---|---|---|---|---|\n");
		for (EvalTrial t : byCase().values().stream().flatMap(List::stream).toList()) {
			md.append(String.format("| %s | %d | %s | %s | %s | $%.4f | %d | %s |%n", t.caseId(), t.trial(),
					t.passed() ? "PASS" : "FAIL", t.finalState(), String.join("<br>", t.failedChecks()),
					t.usage().costMicroUsd() / 1_000_000.0, t.toolCalls(), t.note() == null ? "" : t.note()
							.replace("|", "\\|").replace("\n", " ")));
		}
		return md.toString();
	}
}
