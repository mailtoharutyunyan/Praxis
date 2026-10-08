package io.agenticsdlc.core.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryRefTest {

	@ParameterizedTest
	@ValueSource(strings = { "https://github.com/acme/shop.git", "https://dev.azure.com/acme/shop/_git/shop" })
	void acceptsHttpsUrls(String url) {
		assertThatCode(() -> new RepositoryRef(ScmKind.GITHUB, URI.create(url))).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "file:///etc", "ssh://git@github.com/acme/shop.git", "http://github.com/acme/shop.git",
			"https://user:token@github.com/acme/shop.git" })
	void rejectsUnsafeUrls(String url) {
		assertThatThrownBy(() -> new RepositoryRef(ScmKind.GITHUB, URI.create(url)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
