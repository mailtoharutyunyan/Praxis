package io.agenticsdlc.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Constant-time verification of webhook signatures ({@code X-Hub-Signature: sha256=<hex>}), shared tokens and basic
 * authentication passwords.
 */
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

	/** Hex SHA-256 of a request body, used to identify a signed event. */
	static String sha256(byte[] body) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** HTTP basic authentication ({@code Authorization: Basic base64(user:password)}); only the password counts. */
	static boolean validBasicPassword(String authorization, String expected) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
			return false;
		}
		try {
			String credentials = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
					StandardCharsets.UTF_8);
			int colon = credentials.indexOf(':');
			return colon >= 0 && validToken(credentials.substring(colon + 1), expected);
		}
		catch (IllegalArgumentException e) {
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
