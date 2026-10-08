package io.agenticsdlc.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildPlanTest {

	@Test
	void aRootBuildIsOneComponent() {
		BuildPlan plan = BuildPlan.detect(Set.of("pom.xml", "mvnw", "orders/pom.xml", "src/main/App.java"), null).orElseThrow();
		assertThat(plan.components()).singleElement().satisfies(c -> {
			assertThat(c.path()).isEqualTo(".");
			assertThat(c.profile().tool()).isEqualTo("maven");
		});
	}

	@Test
	void aMonorepoWithoutRootBuildHasOneComponentPerServiceAndToolchain() {
		BuildPlan plan = BuildPlan.detect(Set.of("README.md", "docker-compose.yml",
				"services/orders/pom.xml", "services/orders/mvnw", "services/orders/src/main/java/App.java",
				"services/web/package.json", "services/web/package-lock.json", "services/web/node_modules/x/package.json",
				"tools/billing/go.mod", "docs/site/package.json", "services/orders/sub/pom.xml"), null).orElseThrow();

		assertThat(plan.components()).extracting(BuildPlan.Component::name, BuildPlan.Component::path, c -> c.profile().tool())
				.containsExactly(org.assertj.core.groups.Tuple.tuple("orders", "services/orders", "maven"),
						org.assertj.core.groups.Tuple.tuple("web", "services/web", "npm"),
						org.assertj.core.groups.Tuple.tuple("billing", "tools/billing", "go"));
		assertThat(plan.main().name()).isEqualTo("orders");
		assertThat(plan.components().getFirst().inDirectory("./mvnw -B verify"))
				.isEqualTo("cd 'services/orders' && ./mvnw -B verify");
	}

	@Test
	void onlyTouchedServicesAreAffectedAndSharedFilesAffectAll() {
		BuildPlan plan = BuildPlan.detect(Set.of("a/pom.xml", "b/package.json", "b/package-lock.json"), null).orElseThrow();
		assertThat(plan.affected(List.of("b/src/index.ts"))).extracting(BuildPlan.Component::name).containsExactly("b");
		assertThat(plan.affected(List.of("a/x.java", "b/y.ts"))).hasSize(2);
		assertThat(plan.affected(List.of("docker-compose.yml"))).hasSize(2);
		assertThat(plan.affected(List.of())).isEmpty();
	}

	@Test
	void configuredServicesSidecarsAndEnvWin() {
		ProjectConfig config = new ProjectConfig(null, null, null, null,
				List.of(new ProjectConfig.ServiceConfig("Orders API", "svc/orders", null, null, null, "./mvnw -B verify -Pit"),
						new ProjectConfig.ServiceConfig(null, "ui", "node:24", null, "npm run build", "npm test")),
				List.of(new ProjectConfig.SidecarConfig("postgres", "postgres:16", Map.of("POSTGRES_PASSWORD", "t"),
						"pg_isready")),
				Map.of("DB_URL", "jdbc:postgresql://postgres/postgres"));
		BuildPlan plan = BuildPlan.detect(Set.of("svc/orders/pom.xml", "svc/orders/mvnw", "ui/index.html"), config)
				.orElseThrow();
		assertThat(plan.components()).extracting(BuildPlan.Component::name).containsExactly("orders-api", "ui");
		assertThat(plan.components().getFirst().profile().test()).isEqualTo("./mvnw -B verify -Pit");
		assertThat(plan.components().get(1).profile().image()).isEqualTo("node:24");
		assertThat(plan.sidecars()).extracting(ProjectConfig.SidecarConfig::name).containsExactly("postgres");
		assertThat(plan.env()).containsKey("DB_URL");
	}

	@Test
	void duplicateServiceNamesUseTheirPaths() {
		BuildPlan plan = BuildPlan.detect(Set.of("java/api/pom.xml", "node/api/package.json"), null).orElseThrow();
		assertThat(plan.components()).extracting(BuildPlan.Component::name).containsExactly("java-api", "node-api");
	}

	@Test
	void nothingRecognisableIsEmpty() {
		assertThat(BuildPlan.detect(Set.of("README.md", "docs/index.md"), null)).isEmpty();
		assertThat(BuildPlan.detect(Set.of("x/pom.xml"), new ProjectConfig(null, null, null, null,
				List.of(new ProjectConfig.ServiceConfig("x", "y", null, null, null, null)), List.of(), Map.of()))).isEmpty();
	}
}
