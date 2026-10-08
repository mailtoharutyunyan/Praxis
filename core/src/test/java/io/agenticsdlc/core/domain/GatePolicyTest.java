package io.agenticsdlc.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GatePolicyTest {

	@ParameterizedTest
	@EnumSource(RiskLevel.class)
	void publishGateIsAlwaysRequired(RiskLevel risk) {
		assertThat(GatePolicy.forRisk(risk, Trust.TRUSTED).requires(Gate.PUBLISH)).isTrue();
		assertThat(GatePolicy.forRisk(risk, Trust.UNTRUSTED).requires(Gate.PUBLISH)).isTrue();
	}

	@Test
	void gatesScaleWithRisk() {
		assertThat(GatePolicy.forRisk(RiskLevel.LOW, Trust.TRUSTED).gates()).containsExactly(Gate.PUBLISH);
		assertThat(GatePolicy.forRisk(RiskLevel.MEDIUM, Trust.TRUSTED).gates())
				.containsExactlyInAnyOrder(Gate.SPEC, Gate.PUBLISH);
		assertThat(GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED).gates())
				.containsExactlyInAnyOrder(Gate.values());
	}

	@Test
	void untrustedTextAlwaysNeedsSpecApproval() {
		assertThat(GatePolicy.forRisk(RiskLevel.LOW, Trust.UNTRUSTED).gates())
				.containsExactlyInAnyOrder(Gate.SPEC, Gate.PUBLISH);
	}

	@Test
	void iteratesInDeclarationOrderAndUnionKeepsAllGates() {
		GatePolicy low = GatePolicy.forRisk(RiskLevel.LOW, Trust.TRUSTED);
		GatePolicy medium = GatePolicy.forRisk(RiskLevel.MEDIUM, Trust.TRUSTED);
		assertThat(low.union(medium).gates()).containsExactly(Gate.SPEC, Gate.PUBLISH);
		assertThat(GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED).gates())
				.containsExactly(Gate.SPEC, Gate.IMPLEMENTATION, Gate.PUBLISH);
	}

	@Test
	void rejectsPolicyWithoutPublishGate() {
		assertThatThrownBy(() -> new GatePolicy(Set.of(Gate.SPEC)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PUBLISH");
	}
}
