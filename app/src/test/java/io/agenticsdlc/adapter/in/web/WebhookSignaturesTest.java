package io.agenticsdlc.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignaturesTest {

	private static final byte[] BODY = "Hello World!".getBytes(StandardCharsets.UTF_8);

	@Test
	void matchesAtlassiansPublishedTestVector() {
		assertThat(WebhookSignatures.validHubSignature(
				"sha256=a4771c39fbe90f317c7824e83ddef3caae9cb3d976c214ace1f2937e133263c9", BODY,
				"It's a Secret to Everybody")).isTrue();
	}

	@Test
	void rejectsTamperingWrongSecretsAndOtherAlgorithms() {
		String good = "sha256=a4771c39fbe90f317c7824e83ddef3caae9cb3d976c214ace1f2937e133263c9";
		assertThat(WebhookSignatures.validHubSignature(good, "Hello World?".getBytes(), "It's a Secret to Everybody"))
				.isFalse();
		assertThat(WebhookSignatures.validHubSignature(good, BODY, "another secret")).isFalse();
		assertThat(WebhookSignatures.validHubSignature(good.replace("sha256", "sha1"), BODY, "It's a Secret to Everybody"))
				.isFalse();
		assertThat(WebhookSignatures.validHubSignature("sha256=zz", BODY, "s")).isFalse();
		assertThat(WebhookSignatures.validHubSignature(null, BODY, "s")).isFalse();
		assertThat(WebhookSignatures.validHubSignature(good, BODY, "")).isFalse();
	}

	@Test
	void sharedTokensCompareExactly() {
		assertThat(WebhookSignatures.validToken("t0k3n", "t0k3n")).isTrue();
		assertThat(WebhookSignatures.validToken("t0k3n-x", "t0k3n")).isFalse();
		assertThat(WebhookSignatures.validToken(null, "t0k3n")).isFalse();
		assertThat(WebhookSignatures.validToken("", "")).isFalse();
	}
}
