package io.agenticsdlc.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The REST API's OpenAPI description ({@code /v3/api-docs}): one bearer scheme for JWTs and API tokens. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	@Bean
	OpenAPI agenticOpenApi() {
		return new OpenAPI()
				.info(new Info().title("Agentic SDLC API").version("v1")
						.description("Turns tasks into reviewed pull requests. Authenticate with a bearer token: a "
								+ "personal API token (asdlc_…, from the web UI) or a JWT from your identity provider. "
								+ "Errors are RFC 9457 problem details.")
						.license(new License().name("Proprietary")))
				.components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
						.type(SecurityScheme.Type.HTTP).scheme("bearer")
						.description("Personal API token (asdlc_…) or JWT")))
				.addSecurityItem(new SecurityRequirement().addList("bearer"));
	}
}
