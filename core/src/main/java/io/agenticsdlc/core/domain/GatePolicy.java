package io.agenticsdlc.core.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The set of gates a run must pass. {@link Gate#PUBLISH} is always included; the rest scale with risk.
 * Untrusted task text adds the {@link Gate#SPEC} gate so a human confirms what the agent will do
 * before it acts on text an outsider wrote. A task that asks for plan review also gets {@link Gate#SPEC}.
 * Neither rule ever removes a gate. Iteration order is the gate declaration order.
 */
public record GatePolicy(Set<Gate> gates) {

	public GatePolicy {
		Objects.requireNonNull(gates, "gates");
		if (!gates.contains(Gate.PUBLISH)) {
			throw new IllegalArgumentException("PUBLISH gate is mandatory");
		}
		gates = Collections.unmodifiableSet(EnumSet.copyOf(gates));
	}

	public static GatePolicy forRisk(RiskLevel risk, Trust trust) {
		return forRisk(risk, trust, false);
	}

	public static GatePolicy forRisk(RiskLevel risk, Trust trust, boolean reviewPlan) {
		Objects.requireNonNull(risk, "risk");
		Objects.requireNonNull(trust, "trust");
		EnumSet<Gate> gates = switch (risk) {
			case LOW -> EnumSet.of(Gate.PUBLISH);
			case MEDIUM -> EnumSet.of(Gate.SPEC, Gate.PUBLISH);
			case HIGH -> EnumSet.allOf(Gate.class);
		};
		if (trust == Trust.UNTRUSTED || reviewPlan) {
			gates.add(Gate.SPEC);
		}
		return new GatePolicy(gates);
	}

	public boolean requires(Gate gate) {
		return gates.contains(gate);
	}

	/** Gates of both policies. Used when risk is raised: a run never loses a gate it already had. */
	public GatePolicy union(GatePolicy other) {
		EnumSet<Gate> merged = EnumSet.copyOf(gates);
		merged.addAll(other.gates);
		return new GatePolicy(merged);
	}
}
