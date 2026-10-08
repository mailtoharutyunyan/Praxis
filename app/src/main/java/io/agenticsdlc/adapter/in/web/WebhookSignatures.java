package io.agenticsdlc.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Constant-time verification of webhook signatures ({@code X-Hub-Signature: sha256=<hex>}) and shared tokens. */
final class WebhookSignatures {

	private WebhookSignatures() {
	}

	/** WebSub style: the algorithm name precedes {@code =}; only sha256 is accepted. */
	static boolean validHubSignature(String header, byte[] body, String secret) {
		if (header == null || secret == null || secret.isBlank()) {
			return false;
		}
		int eq = header.indexOf('=');
		if (eq < 0 || !header.substring(0, eq).trim().toLowerCase(Locale.ROOT).equals("sha256")) {
			return false;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] expected = mac.doFinal(body);
			byte[] actual = HexFormat.of().parseHex(header.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
			return MessageDigest.isEqual(expected, actual);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
			return false;
		}
	}

	static boolean validToken(String presented, String expected) {
		if (presented == null || expected == null || expected.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(presented.trim().getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}
}
