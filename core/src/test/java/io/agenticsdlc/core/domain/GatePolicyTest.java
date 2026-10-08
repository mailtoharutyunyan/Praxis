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
	void planReviewAddsSpecGate() {
		assertThat(GatePolicy.forRisk(RiskLevel.LOW, Trust.TRUSTED, true).gates())
				.containsExactly(Gate.SPEC, Gate.PUBLISH);
		assertThat(GatePolicy.forRisk(RiskLevel.MEDIUM, Trust.TRUSTED, true).gates())
				.containsExactly(Gate.SPEC, Gate.PUBLISH);
		assertThat(GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED, true))
				.isEqualTo(GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED));
		assertThat(GatePolicy.forRisk(RiskLevel.HIGH, Trust.TRUSTED, true).gates())
				.containsExactly(Gate.SPEC, Gate.IMPLEMENTATION, Gate.PUBLISH);
	}

	@Test
	void withoutPlanReviewGatesAreUnchanged() {
		for (RiskLevel risk : RiskLevel.values()) {
			for (Trust trust : Trust.values()) {
				assertThat(GatePolicy.forRisk(risk, trust, false).gates())
						.containsExactlyElementsOf(GatePolicy.forRisk(risk, trust).gates());
			}
		}
	}

	@ParameterizedTest
	@EnumSource(RiskLevel.class)
	void planReviewOnlyEverAddsGates(RiskLevel risk) {
		for (Trust trust : Trust.values()) {
			GatePolicy reviewed = GatePolicy.forRisk(risk, trust, true);
			assertThat(reviewed.gates()).containsAll(GatePolicy.forRisk(risk, trust, false).gates());
			assertThat(reviewed.requires(Gate.SPEC)).isTrue();
			assertThat(reviewed.requires(Gate.PUBLISH)).isTrue();
		}
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
