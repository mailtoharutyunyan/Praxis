package io.agenticsdlc.adapter.out.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.SandboxSpec;
import io.agenticsdlc.support.TestRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** Runs against the local Docker engine (the same one Testcontainers uses). */
class DockerSandboxTest {

	private static DockerClient docker;

	@TempDir
	Path tmp;

	private DockerSandbox sandbox;
	private UUID runId;
	private WorkspacePaths paths;

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

	@BeforeEach
	void setUp() throws Exception {
		paths = new WorkspacePaths(tmp);
		runId = UUID.randomUUID();
		Files.createDirectories(paths.repo(runId));
		Files.writeString(paths.repo(runId).resolve("hello.txt"), "hello\n");
		AgenticProperties.Sandbox settings = new AgenticProperties.Sandbox(true, tmp, "", "none", "", "",
				DataSize.ofMegabytes(256), 1, 128, "", Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000);
		sandbox = new DockerSandbox(docker, paths, settings);
		sandbox.start(runId, new SandboxSpec(TestRepos.ALPINE, Map.of("GREETING", "hi"))).block();
	}

	@AfterEach
	void tearDown() {
		sandbox.destroy(runId).block();
	}

	private CommandResult run(String command, Duration timeout) {
		return sandbox.exec(runId, command, timeout).block();
	}

	@Test
	void runsCommandsInMountedWorkspace() throws Exception {
		CommandResult cat = run("cat hello.txt && echo $GREETING && pwd", Duration.ofSeconds(30));
		assertThat(cat.succeeded()).isTrue();
		assertThat(cat.output()).contains("hello", "hi", "/workspace");

		run("echo written > out.txt", Duration.ofSeconds(30));
		assertThat(Files.readString(paths.repo(runId).resolve("out.txt"))).isEqualTo("written\n");
	}

	@Test
	void reportsExitCodesAndStderr() {
		CommandResult failed = run("echo boom >&2; exit 3", Duration.ofSeconds(30));
		assertThat(failed.exitCode()).isEqualTo(3);
		assertThat(failed.succeeded()).isFalse();
		assertThat(failed.output()).contains("boom");
	}

	@Test
	void killsCommandsThatExceedTheTimeout() {
		CommandResult slow = run("sleep 30", Duration.ofSeconds(1));
		assertThat(slow.timedOut()).isTrue();
		assertThat(slow.took()).isLessThan(Duration.ofSeconds(20));
	}

	@Test
	void truncatesLongOutputKeepingTheTail() {
		CommandResult noisy = run("i=0; while [ $i -lt 2000 ]; do echo line-$i; i=$((i+1)); done; echo THE-END",
				Duration.ofSeconds(30));
		assertThat(noisy.truncated()).isTrue();
		assertThat(noisy.output()).startsWith("line-0").contains("characters omitted").endsWith("THE-END\n");
		assertThat(noisy.output().length()).isLessThan(2_200);
	}

	@Test
	void servicesGetTheirOwnEnvironmentsAndReachSidecarsOnAPrivateNetwork() {
		UUID run = UUID.randomUUID();
		java.nio.file.Path repo = paths.repo(run);
		try {
			Files.createDirectories(repo);
			Files.writeString(repo.resolve("hello.txt"), "hello\n");
			sandbox.startSidecars(run, List.of(new io.agenticsdlc.core.workspace.ProjectConfig.SidecarConfig("postgres",
					"postgres:16-alpine", Map.of("POSTGRES_PASSWORD", "test"), "pg_isready -U postgres"))).block();
			sandbox.start(run, new SandboxSpec(TestRepos.ALPINE, Map.of("DB_HOST", "postgres"))).block();
			sandbox.start(run, new SandboxSpec("web", TestRepos.ALPINE, Map.of())).block();

			CommandResult reach = sandbox.exec(run, "nc -z -w 5 \"$DB_HOST\" 5432 && echo reachable", Duration.ofSeconds(30)).block();
			assertThat(reach.output()).as(reach.output()).contains("reachable");
			assertThat(sandbox.exec(run, "web", "cat hello.txt", Duration.ofSeconds(30)).block().output()).contains("hello");
			assertThat(sandbox.exec(run, "wget -q -T 3 -O- http://example.com", Duration.ofSeconds(30)).block().succeeded())
					.as("the private network has no way out").isFalse();
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		finally {
			sandbox.destroy(run).block();
		}
		assertThat(docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(DockerSandbox.LABEL_RUN, run.toString()))
				.exec()).isEmpty();
		assertThat(docker.listNetworksCmd().withNameFilter(DockerSandbox.networkName(run)).exec()).isEmpty();
	}

	@Test
	void containerIsHardened() {
		InspectContainerResponse container = docker.inspectContainerCmd(DockerSandbox.containerName(runId)).exec();
		assertThat(container.getConfig().getUser()).isNotBlank().isNotEqualTo("0:0").isNotEqualTo("root");
		assertThat(container.getHostConfig().getSecurityOpts()).contains("no-new-privileges");
		assertThat(container.getHostConfig().getCapDrop()).isNotEmpty();
		assertThat(container.getHostConfig().getNetworkMode()).isEqualTo("none");
		assertThat(container.getHostConfig().getMemory()).isEqualTo(DataSize.ofMegabytes(256).toBytes());
		assertThat(container.getConfig().getEnv()).noneMatch(e -> e.toLowerCase().contains("token"));
		assertThat(run("id -u", Duration.ofSeconds(30)).output().trim()).isNotEqualTo("0");
	}

	@Test
	void proxySettingsReachEveryBuildTool() {
		UUID proxied = UUID.randomUUID();
		DockerSandbox withProxy = new DockerSandbox(docker, paths, settings("none", "http://egress:3128", ""));
		try {
			withProxy.start(proxied, new SandboxSpec(TestRepos.ALPINE, Map.of())).block();
			String out = withProxy.exec(proxied, "echo $HTTPS_PROXY $no_proxy; echo $MAVEN_ARGS; cat " + DockerSandbox.MAVEN_SETTINGS,
					Duration.ofSeconds(30)).block().output();
			assertThat(out).contains("http://egress:3128 localhost,127.0.0.1", "-gs " + DockerSandbox.MAVEN_SETTINGS,
					"<host>egress</host><port>3128</port>", "<protocol>https</protocol>");
		}
		finally {
			withProxy.destroy(proxied).block();
		}
	}

	@Test
	void unsafeConfigurationsAreRefusedAtStartup() {
		assertThatThrownBy(() -> new DockerSandbox(docker, paths, settings("host", "", "")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host");
		assertThatThrownBy(() -> new DockerSandbox(docker, paths, settings("none", "", "0:0")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("root");
		assertThatThrownBy(() -> new DockerSandbox(docker, paths, settings("none", "", "root")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("root");
		assertThatThrownBy(() -> new DockerSandbox(docker, paths, settings("none", "egress", "")))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host and port");
	}

	@Test
	void mavenSettingsEscapeValues() {
		assertThat(DockerSandbox.mavenSettings(java.net.URI.create("http://proxy:8080"), "a,<b>"))
				.contains("<nonProxyHosts>a|&lt;b&gt;</nonProxyHosts>");
	}

	private AgenticProperties.Sandbox settings(String network, String proxy, String user) {
		return new AgenticProperties.Sandbox(true, tmp, "", network, proxy, "localhost,127.0.0.1",
				DataSize.ofMegabytes(256), 1, 128, user, Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000);
	}

	@Test
	void startIsIdempotentAndDestroyToo() {
		sandbox.start(runId, new SandboxSpec(TestRepos.ALPINE, Map.of())).block();
		assertThat(run("true", Duration.ofSeconds(30)).succeeded()).isTrue();
		sandbox.destroy(runId).block();
		sandbox.destroy(runId).block();
		sandbox.start(runId, new SandboxSpec(TestRepos.ALPINE, Map.of())).block();
		assertThat(run("cat hello.txt", Duration.ofSeconds(30)).output()).contains("hello");
	}

	@Test
	void readsAndWritesFilesAsTheContainerUser() throws Exception {
		assertThat(sandbox.readFile(runId, "hello.txt", 1_000).block()).isEqualTo("hello\n");

		String content = "line 1\nüñíçødé ✓\n" + "x".repeat(100_000) + "\n";
		sandbox.writeFile(runId, "src/deep/New.java", content).block();
		assertThat(Files.readString(paths.repo(runId).resolve("src/deep/New.java"))).isEqualTo(content);
		assertThat(sandbox.readFile(runId, "src/deep/New.java", 200_000).block()).isEqualTo(content);
		assertThat(run("stat -c %u src/deep/New.java", Duration.ofSeconds(30)).output().trim())
				.isEqualTo(run("id -u", Duration.ofSeconds(30)).output().trim());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sandbox.readFile(runId, "missing.txt", 1_000).block())
				.hasCauseInstanceOf(java.nio.file.NoSuchFileException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sandbox.readFile(runId, "src", 1_000).block())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a regular file");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sandbox.readFile(runId, "src/deep/New.java", 10).block())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("larger than");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sandbox.writeFile(runId, "src", "x").block())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is a directory");
	}

	@Test
	void symlinksResolveInsideTheContainerNotOnTheHost() throws Exception {
		Path hostSecret = tmp.resolve("host-secret.txt");
		Files.writeString(hostSecret, "host only\n");
		run("ln -s " + hostSecret + " leak.txt", Duration.ofSeconds(30));
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> sandbox.readFile(runId, "leak.txt", 1_000).block())
				.hasCauseInstanceOf(java.nio.file.NoSuchFileException.class);
	}

	@Test
	void boundedOutputKeepsShortOutputIntact() {
		BoundedOutput out = new BoundedOutput(100);
		out.append("abc");
		out.append("def");
		assertThat(out.truncated()).isFalse();
		assertThat(out.toString()).isEqualTo("abcdef");
	}
}
