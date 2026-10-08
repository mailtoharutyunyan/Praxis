package io.agenticsdlc.adapter.out.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.workspace.SecurityScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real gitleaks image against a working copy (offline); OSV output is parsed from a recorded sample. */
class DockerSecurityScannerTest {

	private static final String SECRET = "kd8Y7sa6Hds7Hasf7aS8dhaS9dh7HAs8hd7Hasd9";
	private static DockerClient docker;
	private final JsonMapper json = JsonMapper.builder().build();

	@TempDir
	Path tmp;

	@BeforeAll
	static void connect() {
		DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
		docker = DockerClientImpl.getInstance(config, new ZerodepDockerHttpClient.Builder()
				.dockerHost(config.getDockerHost()).build());
	}

	@AfterAll
	static void disconnect() throws Exception {
		docker.close();
	}

	@Test
	void secretsInChangedFilesBlockAndAreNeverPrinted() throws Exception {
		WorkspacePaths paths = new WorkspacePaths(tmp);
		UUID runId = UUID.randomUUID();
		Path repo = Files.createDirectories(paths.repo(runId));
		String credentials = "aws_access_key_id = \"AKIA" + "QWERTYUIOPASDFGH\"\naws_secret_access_key = \"" + SECRET + "\"\n";
		Files.writeString(repo.resolve("config.ini"), credentials);
		Files.writeString(repo.resolve("legacy.ini"), credentials);
		Files.writeString(repo.resolve("pom.xml"), "<project/>");
		AgenticProperties.Sandbox sandboxSettings = new AgenticProperties.Sandbox(true, tmp, "", "none", "", "",
				DataSize.ofMegabytes(256), 1, 128, "", Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000);
		DockerSecurityScanner scanner = new DockerSecurityScanner(docker, paths, new DockerSandbox(docker, paths,
				sandboxSettings), sandboxSettings, new AgenticProperties.Scan(true, "zricethezav/gitleaks:v8.30.1", true,
						"ghcr.io/google/osv-scanner:v2.6.0", Duration.ofMinutes(2)), json);

		SecurityScanner.Report report = scanner.scan(runId, List.of("config.ini", "pom.xml")).block();

		assertThat(report.blocking()).isNotEmpty().allSatisfy(f -> {
			assertThat(f.file()).isEqualTo("config.ini");
			assertThat(f.tool()).isEqualTo("gitleaks");
		});
		assertThat(report.findings()).noneMatch(f -> f.file().equals("legacy.ini"));
		assertThat(report.summary()).doesNotContain(SECRET).doesNotContain("AKIA" + "QWERTYUIOPASDFGH")
				.contains("Dependency scan skipped");
		assertThat(scanner.scan(runId, List.of()).block()).isEqualTo(SecurityScanner.Report.EMPTY);
		assertThat(docker.listContainersCmd().withShowAll(true).withLabelFilter(List.of(DockerSecurityScanner.LABEL_SCAN))
				.exec()).noneMatch(c -> runId.toString().equals(c.getLabels().get(DockerSecurityScanner.LABEL_SCAN)));
	}

	@Test
	void vulnerableDependenciesInChangedManifestsAreAdvisory() {
		String sample = """
				{"results":[{"source":{"path":"/scan/pom.xml","type":"lockfile"},"packages":[{"package":{
				"name":"org.apache.logging.log4j:log4j-core","version":"2.14.1","ecosystem":"Maven"},
				"groups":[{"ids":["GHSA-jfh8-c2jp-5v3q","CVE-2021-44228"],"max_severity":"10.0"},
				{"ids":["GHSA-8489-44mv-ggj8"],"max_severity":"6.6"}]}]},
				{"source":{"path":"/scan/old/package-lock.json"},"packages":[{"package":{"name":"x","version":"1"},
				"groups":[{"ids":["GHSA-1"],"max_severity":"9.8"}]}]}]}""";
		List<SecurityScanner.Finding> findings = DockerSecurityScanner.vulnerabilities(json.readTree(sample),
				Set.of("pom.xml"));
		assertThat(findings).hasSize(2).noneMatch(SecurityScanner.Finding::blocking);
		assertThat(findings.getFirst().severity()).isEqualTo("CRITICAL");
		assertThat(findings.getFirst().message()).contains("log4j-core@2.14.1", "CVE-2021-44228");
		assertThat(findings.get(1).severity()).isEqualTo("MEDIUM");
		assertThat(DockerSecurityScanner.isManifest("services/api/package-lock.json")).isTrue();
		assertThat(DockerSecurityScanner.isManifest("src/App.csproj")).isTrue();
		assertThat(DockerSecurityScanner.isManifest("src/Main.java")).isFalse();
	}
}
