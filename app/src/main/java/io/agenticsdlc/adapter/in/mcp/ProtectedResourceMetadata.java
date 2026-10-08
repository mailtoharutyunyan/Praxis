package io.agenticsdlc.adapter.in.mcp;

import io.agenticsdlc.config.AgenticProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) for the MCP endpoint: tells MCP clients which authorization server
 * issues tokens for this server, so they can sign the user in on their own. The 401 challenge on {@code /mcp} points
 * here (see {@code SecurityConfiguration}).
 */
@RestController
public class ProtectedResourceMetadata {

	public static final String PATH = "/.well-known/oauth-protected-resource";

	private final String issuer;
	private final String resource;
	private final String mcpEndpoint;

	ProtectedResourceMetadata(AgenticProperties properties,
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
			@Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint) {
		this.issuer = issuerUri.isBlank() ? properties.ui().issuer() : issuerUri;
		this.resource = properties.mcp().resource();
		this.mcpEndpoint = mcpEndpoint;
	}

	@GetMapping({ PATH, PATH + "/mcp" })
	Map<String, Object> metadata(ServerHttpRequest request) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("resource", resource(request));
		List<String> servers = new ArrayList<>();
		if (!issuer.isBlank()) {
			servers.add(issuer);
		}
		metadata.put("authorization_servers", servers);
		metadata.put("bearer_methods_supported", List.of("header"));
		metadata.put("resource_name", "Agentic SDLC");
		return metadata;
	}

	/** The configured public MCP URL, else this request's origin plus the MCP endpoint. */
	String resource(ServerHttpRequest request) {
		if (!resource.isBlank()) {
			return resource;
		}
		return UriComponentsBuilder.fromUri(request.getURI()).replacePath(mcpEndpoint).replaceQuery(null).build()
				.toUriString();
	}

	/** Where the metadata lives, for the {@code resource_metadata} parameter of a 401 challenge. */
	public static String metadataUrl(ServerHttpRequest request) {
		return UriComponentsBuilder.fromUri(request.getURI()).replacePath(PATH + "/mcp").replaceQuery(null).build()
				.toUriString();
	}
}
