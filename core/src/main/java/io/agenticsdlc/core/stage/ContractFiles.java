package io.agenticsdlc.core.stage;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * API contract files of service-to-service interfaces: OpenAPI/Swagger, AsyncAPI, protobuf, GraphQL schemas, Avro
 * and WSDL. Changing one can break consumers in other services or repositories (ADR-0006).
 */
public final class ContractFiles {

	private static final Pattern CONTRACT = Pattern.compile(
			"(openapi|swagger|asyncapi)[^/]*\\.(ya?ml|json)|.*\\.(proto|graphql|graphqls|gql|avsc|avdl|wsdl)");

	private ContractFiles() {
	}

	public static boolean isContract(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		return CONTRACT.matcher(name).matches();
	}

	public static List<String> changed(Collection<String> changedFiles) {
		return changedFiles.stream().filter(ContractFiles::isContract).toList();
	}

	/** What reviewers should check; null when no contract changed. */
	public static String note(Collection<String> changedFiles) {
		List<String> contracts = changed(changedFiles);
		if (contracts.isEmpty()) {
			return null;
		}
		return "API contract changed: " + String.join(", ", contracts) + ". Check backward compatibility for its "
				+ "consumers (removed or renamed fields and endpoints, changed types, new required fields); consumers in "
				+ "other repositories may need the same change as companion repositories.";
	}
}
