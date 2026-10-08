package io.agenticsdlc.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BuildProfileTest {

	@ParameterizedTest
	@CsvSource({
			"'pom.xml,mvnw', maven, './mvnw -B -ntp verify'",
			"'pom.xml', maven, 'mvn -B -ntp verify'",
			"'build.gradle.kts,gradlew', gradle, './gradlew --no-daemon check'",
			"'build.gradle', gradle, 'gradle --no-daemon check'",
			"'package.json,package-lock.json', npm, 'npm test'",
			"'package.json,pnpm-lock.yaml', pnpm, 'pnpm test'",
			"'package.json,yarn.lock', yarn, 'yarn test'",
			"'go.mod', go, 'go test ./...'",
			"'pyproject.toml', python, 'python -m pytest'",
			"'Shop.sln', dotnet, 'dotnet test --no-restore'" })
	void detectsToolchains(String files, String tool, String test) {
		BuildProfile profile = BuildProfile.detect(Set.of(files.split(",")), null).orElseThrow();
		assertThat(profile.tool()).isEqualTo(tool);
		assertThat(profile.test()).isEqualTo(test);
	}

	@Test
	void npmUsesCiOnlyWithLockfile() {
		assertThat(BuildProfile.detect(Set.of("package.json"), null).orElseThrow().setup()).isEqualTo("npm install");
		assertThat(BuildProfile.detect(Set.of("package.json", "package-lock.json"), null).orElseThrow().setup())
				.isEqualTo("npm ci");
	}

	@Test
	void projectConfigOverridesDetectedValues() {
		BuildProfile profile = BuildProfile.detect(Set.of("pom.xml", "mvnw"),
				new ProjectConfig("maven:3.9-eclipse-temurin-21", null, null, "./mvnw -B test")).orElseThrow();
		assertThat(profile.image()).isEqualTo("maven:3.9-eclipse-temurin-21");
		assertThat(profile.build()).isEqualTo("./mvnw -B -ntp -DskipTests test-compile");
		assertThat(profile.test()).isEqualTo("./mvnw -B test");
	}

	@Test
	void unknownRepositoryNeedsCompleteConfig() {
		assertThat(BuildProfile.detect(Set.of("README.md"), null)).isEmpty();
		assertThat(BuildProfile.detect(Set.of("README.md"), new ProjectConfig("alpine", null, "true", null))).isEmpty();
		BuildProfile custom = BuildProfile.detect(Set.of("Makefile"), new ProjectConfig("alpine", null, "make", "make test"))
				.orElseThrow();
		assertThat(custom.tool()).isEqualTo("custom");
		assertThat(custom.verifyCommands()).containsExactly("make", "make test");
	}

	@Test
	void verifyCommandsDeduplicate() {
		assertThat(new BuildProfile("x", "img", null, "make check", "make check").verifyCommands())
				.containsExactly("make check");
	}

	@Test
	void commandResultHelpers() {
		CommandResult ok = new CommandResult("true", 0, "fine", false, false, Duration.ofMillis(5));
		CommandResult slow = new CommandResult("sleep", 0, "", false, true, Duration.ofSeconds(9));
		assertThat(ok.succeeded()).isTrue();
		assertThat(slow.succeeded()).isFalse();
		assertThat(new CommandResult("x", 1, "abcdef", false, false, Duration.ZERO).tail(3)).isEqualTo("…def");
		assertThat(ok.toPayload()).containsEntry("exitCode", 0).containsEntry("durationMs", 5L);
	}
}
