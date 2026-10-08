package io.agenticsdlc.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UsageTest {

	@Test
	void theBudgetCountsCacheReadsAtATenthLikeTheirPrice() {
		// A long Claude Code stage seen live: mostly cache reads, which cost a tenth of fresh input.
		Usage usage = new Usage(136, 42_112, 1_889_251, 179_081, 2_650_000);
		assertThat(usage.totalTokens()).isEqualTo(2_110_580);
		assertThat(usage.budgetTokens()).isEqualTo(136 + 42_112 + 179_081 + 188_925);
	}
}
