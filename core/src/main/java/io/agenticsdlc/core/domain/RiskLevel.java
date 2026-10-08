package io.agenticsdlc.core.domain;

/** Triage outcome. Decides how many human gates a run must pass (see {@link GatePolicy}). */
public enum RiskLevel {
	/** Small, local change: typo, config tweak, isolated bug fix. */
	LOW,
	/** Ordinary feature or fix touching a few files within one module. */
	MEDIUM,
	/** Architectural or cross-cutting change, schema/API contract change, security-sensitive code. */
	HIGH
}
