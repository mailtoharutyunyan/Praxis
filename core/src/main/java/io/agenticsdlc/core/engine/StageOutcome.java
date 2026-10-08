package io.agenticsdlc.core.engine;

import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Usage;
import java.util.Map;
import java.util.Objects;

/**
 * What a stage handler reports back. Handlers never choose the next state: {@link Transitions} does, so no
 * handler can skip a gate.
 */
public sealed interface StageOutcome {

	/** Model usage spent by the stage, added to the run's totals and checked against the budget. */
	Usage usage();

	/** The stage did its job; the run moves on (possibly into a gate). Summary values must not be null. */
	record Completed(Usage usage, Map<String, Object> summary) implements StageOutcome {
		public Completed {
			Objects.requireNonNull(usage, "usage");
			summary = summary == null ? Map.of() : Map.copyOf(summary);
		}

		public static Completed free() {
			return new Completed(Usage.ZERO, Map.of());
		}
	}

	/** Triage result. Only valid for the TRIAGING stage. */
	record Triaged(RiskLevel risk, String rationale, Usage usage) implements StageOutcome {
		public Triaged {
			Objects.requireNonNull(risk, "risk");
			Objects.requireNonNull(rationale, "rationale");
			Objects.requireNonNull(usage, "usage");
		}
	}

	/** Verification failed or the reviewer asked for changes. Only valid for VERIFYING and REVIEWING. */
	record NeedsRework(String reason, Usage usage) implements StageOutcome {
		public NeedsRework {
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(usage, "usage");
		}
	}

	/** The stage cannot proceed without a human (ambiguous task, missing access, agent question). */
	record Escalate(String reason, Usage usage) implements StageOutcome {
		public Escalate {
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(usage, "usage");
		}
	}

	/** The run cannot succeed (e.g. repository does not exist). Terminal. */
	record Failed(String reason, Usage usage) implements StageOutcome {
		public Failed {
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(usage, "usage");
		}
	}
}
