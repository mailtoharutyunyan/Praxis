package io.agenticsdlc.core.stage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContractFilesTest {

	@Test
	void recognisesServiceInterfaces() {
		assertThat(ContractFiles.changed(List.of("api/openapi.yaml", "svc/src/main/resources/openapi-v2.json",
				"proto/orders/v1/orders.proto", "schema.graphql", "events/asyncapi.yml", "avro/Order.avsc",
				"src/Main.java", "docs/openapi.md", "swagger.yaml"))).containsExactly("api/openapi.yaml",
						"svc/src/main/resources/openapi-v2.json", "proto/orders/v1/orders.proto", "schema.graphql",
						"events/asyncapi.yml", "avro/Order.avsc", "swagger.yaml");
		assertThat(ContractFiles.note(List.of("src/Main.java"))).isNull();
		assertThat(ContractFiles.note(List.of("orders.proto"))).contains("orders.proto", "backward compatibility");
	}
}
