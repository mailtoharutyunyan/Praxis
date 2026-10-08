package io.agenticsdlc.config.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretBoxTest {

	@TempDir
	Path dir;

	@Test
	void roundTripsWithAFreshIvEachTime() {
		SecretBox box = new SecretBox(new byte[32]);
		String first = box.encrypt("token-value");
		assertThat(first).isNotEqualTo(box.encrypt("token-value")).doesNotContain("token-value");
		assertThat(box.decrypt(first)).isEqualTo("token-value");
	}

	@Test
	void generatesAKeyFileOnceAndReusesIt() throws Exception {
		Path file = dir.resolve("data/secrets.key");
		String sealed = SecretBox.load("", file).encrypt("x");
		assertThat(Base64.getDecoder().decode(Files.readString(file).strip())).hasSize(32);
		assertThat(SecretBox.load(null, file).decrypt(sealed)).isEqualTo("x");
	}

	@Test
	void anExplicitKeyWinsAndTamperingIsDetected() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		SecretBox box = SecretBox.load(key, dir.resolve("unused.key"));
		assertThat(Files.exists(dir.resolve("unused.key"))).isFalse();
		String sealed = box.encrypt("secret");
		byte[] raw = Base64.getDecoder().decode(sealed);
		raw[raw.length - 1] ^= 1;
		assertThatThrownBy(() -> box.decrypt(Base64.getEncoder().encodeToString(raw)))
				.isInstanceOf(IllegalStateException.class);
		byte[] other = new byte[32];
		other[0] = 1;
		assertThatThrownBy(() -> new SecretBox(other).decrypt(sealed)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecretBox(new byte[16])).hasMessageContaining("32 bytes");
	}
}
