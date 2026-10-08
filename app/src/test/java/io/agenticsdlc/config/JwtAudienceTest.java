package io.agenticsdlc.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** The audience configured in application.yaml is enforced, so tokens minted for other apps are rejected. */
class JwtAudienceTest {

	@TempDir
	Path tmp;

	@Test
	void tokensForOtherAudiencesAreRejected() throws Exception {
		KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		Path pem = tmp.resolve("public.pem");
		Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(
				new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate()).build())));

		new ReactiveWebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ReactiveOAuth2ResourceServerAutoConfiguration.class))
				.withPropertyValues("spring.security.oauth2.resourceserver.jwt.public-key-location=file:" + pem,
						"spring.security.oauth2.resourceserver.jwt.audiences=agentic-sdlc")
				.run(context -> {
					ReactiveJwtDecoder decoder = context.getBean(ReactiveJwtDecoder.class);
					assertThat(decoder.decode(token(encoder, List.of("agentic-sdlc"))).block().getSubject()).isEqualTo("alice");
					assertThatThrownBy(() -> decoder.decode(token(encoder, List.of("other-app"))).block())
							.isInstanceOf(JwtValidationException.class).hasMessageContaining("aud");
					assertThatThrownBy(() -> decoder.decode(token(encoder, List.of())).block())
							.isInstanceOf(JwtValidationException.class);
				});
	}

	private static String token(NimbusJwtEncoder encoder, List<String> audience) {
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("alice").issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300));
		if (!audience.isEmpty()) {
			claims.audience(audience);
		}
		return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
	}
}
