package io.agenticsdlc;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}

	/**
	 * Tests authenticate with {@code mockJwt()}; real bearer tokens are rejected. Without a decoder bean the
	 * resource server would need an issuer to start.
	 */
	@Bean
	ReactiveJwtDecoder rejectingJwtDecoder() {
		return token -> Mono.error(new BadJwtException("tests use mockJwt()"));
	}
}
