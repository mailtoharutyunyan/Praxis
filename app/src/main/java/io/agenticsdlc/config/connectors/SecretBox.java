package io.agenticsdlc.config.connectors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypts secrets at rest (connector credentials, the local signing key) with AES-256-GCM. The key is
 * {@code AGENTIC_SECRETS_KEY} (base64, 32 bytes) or, for local installs, a key file generated on first start.
 */
public final class SecretBox {

	private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;
	private final java.util.List<SecretKeySpec> previous;
	private final SecureRandom random = new SecureRandom();

	public SecretBox(byte[] key) {
		this(key, java.util.List.of());
	}

	/** @param previous keys that may still have sealed stored secrets; they only decrypt (key rotation) */
	public SecretBox(byte[] key, java.util.List<byte[]> previous) {
		this.key = spec(key);
		this.previous = previous.stream().map(SecretBox::spec).toList();
	}

	private static SecretKeySpec spec(byte[] key) {
		if (key.length != 32) {
			throw new IllegalArgumentException("the secrets key must be 32 bytes (base64 of 32 random bytes)");
		}
		return new SecretKeySpec(key, "AES");
	}

	/** From {@code base64Key} if set, else from (or generated into) {@code keyFile}. */
	public static SecretBox load(String base64Key, Path keyFile) {
		return load(base64Key, keyFile, java.util.List.of());
	}

	/** @param previousBase64Keys earlier keys, still accepted for decryption until secrets are re-encrypted */
	public static SecretBox load(String base64Key, Path keyFile, java.util.List<String> previousBase64Keys) {
		java.util.List<byte[]> previous = previousBase64Keys.stream().filter(k -> k != null && !k.isBlank())
				.map(k -> Base64.getDecoder().decode(k.strip())).toList();
		if (base64Key != null && !base64Key.isBlank()) {
			return new SecretBox(Base64.getDecoder().decode(base64Key.strip()), previous);
		}
		try {
			if (Files.isRegularFile(keyFile)) {
				return new SecretBox(Base64.getDecoder().decode(Files.readString(keyFile).strip()), previous);
			}
			byte[] generated = new byte[32];
			new SecureRandom().nextBytes(generated);
			Files.createDirectories(keyFile.toAbsolutePath().getParent());
			Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated));
			try {
				Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
			}
			catch (UnsupportedOperationException e) {
				// not a POSIX file system
			}
			log.warn("generated a secrets key in {}; set AGENTIC_SECRETS_KEY in production and back the key up "
					+ "with the database", keyFile);
			return new SecretBox(generated, previous);
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot read or create the secrets key file " + keyFile, e);
		}
	}

	public String encrypt(String plain) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + sealed.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(sealed, 0, out, iv.length, sealed.length);
			return Base64.getEncoder().encodeToString(out);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("encryption failed", e);
		}
	}

	public String decrypt(String encoded) {
		String plain = open(encoded, key);
		for (int i = 0; plain == null && i < previous.size(); i++) {
			plain = open(encoded, previous.get(i));
		}
		if (plain == null) {
			throw new IllegalStateException("cannot decrypt a stored secret; was AGENTIC_SECRETS_KEY changed? Put the "
					+ "old key in AGENTIC_SECRETS_KEY_PREVIOUS");
		}
		return plain;
	}

	/** Whether the current key sealed this value; others are re-encrypted after a key rotation. */
	public boolean sealedWithCurrentKey(String encoded) {
		return open(encoded, key) != null;
	}

	/** The plaintext, or null when this key did not seal it (GCM authentication fails). */
	private static String open(String encoded, SecretKeySpec with) {
		try {
			byte[] in = Base64.getDecoder().decode(encoded);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, with, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
			return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			return null;
		}
	}
}
