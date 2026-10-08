package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "agentic.worker.enabled=false", "agentic.sandbox.enabled=false" })
class AgenticSdlcApplicationTests {

	@Autowired
	DatabaseClient db;

	@Test
	void flywayCreatesSchema() {
		var tables = db.sql("select table_name from information_schema.tables where table_schema = 'public'")
				.map(row -> row.get("table_name", String.class))
				.all()
				.collectList()
				.block();

		assertThat(tables).contains("tasks", "runs", "run_events");
	}
}
