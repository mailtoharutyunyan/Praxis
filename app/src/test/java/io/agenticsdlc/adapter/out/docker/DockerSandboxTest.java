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
				DataSize.ofMegabytes(256), 1, 128, "", Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000, "", "");
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

	/** As in Docker Compose: the app sees the workspace in a volume, sandboxes mount the run's part of it. */
	@Test
	void mountsTheRunsDirectoryFromAWorkspaceVolume() {
		String volume = "agentic-test-workspace-" + UUID.randomUUID();
		UUID run = UUID.randomUUID();
		docker.createVolumeCmd().withName(volume).exec();
		DockerSandbox fromVolume = new DockerSandbox(docker, paths, new AgenticProperties.Sandbox(true, tmp, "", "none",
				"", "", DataSize.ofMegabytes(256), 1, 128, "", Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000, volume, ""));
		try {
			// Seed the volume as the app container would: <run>/repo/hello.txt, owned by the sandbox user.
			String seeder = docker.createContainerCmd(TestRepos.ALPINE)
					.withCmd("sh", "-c", "mkdir -p /ws/" + run + "/repo && echo from-volume > /ws/" + run
							+ "/repo/hello.txt && chown -R " + fromVolume.user() + " /ws/" + run)
					.withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig().withBinds(
							new com.github.dockerjava.api.model.Bind(volume, new com.github.dockerjava.api.model.Volume("/ws"))))
					.exec().getId();
			docker.startContainerCmd(seeder).exec();
			docker.waitContainerCmd(seeder).start().awaitStatusCode(60, java.util.concurrent.TimeUnit.SECONDS);
			docker.removeContainerCmd(seeder).exec();

			fromVolume.start(run, new SandboxSpec(TestRepos.ALPINE, Map.of())).block();
			CommandResult cat = fromVolume.exec(run, "cat hello.txt && echo more > out.txt && ls /workspace",
					Duration.ofSeconds(30)).block();
			assertThat(cat.succeeded()).as(cat.output()).isTrue();
			assertThat(cat.output()).contains("from-volume", "out.txt");
		}
		finally {
			fromVolume.destroy(run).block();
			docker.removeVolumeCmd(volume).exec();
		}
	}

	/** Another install (or a test) on the same Docker host has runs this database does not know: leave them alone. */
	@Test
	void cleanupOnlyTouchesThisInstallationsContainers() throws Exception {
		io.agenticsdlc.core.port.RunStore store = org.mockito.Mockito.mock(io.agenticsdlc.core.port.RunStore.class);
		org.mockito.Mockito.when(store.find(org.mockito.ArgumentMatchers.any())).thenReturn(reactor.core.publisher.Mono.empty());
		io.agenticsdlc.core.workspace.RepositoryCheckout checkout =
				org.mockito.Mockito.mock(io.agenticsdlc.core.workspace.RepositoryCheckout.class);
		org.mockito.Mockito.when(checkout.remove(org.mockito.ArgumentMatchers.any())).thenReturn(reactor.core.publisher.Mono.empty());
		Path otherRoot = tmp.resolve("other-install");
		WorkspacePaths otherPaths = new WorkspacePaths(otherRoot);
		UUID foreign = UUID.randomUUID();
		Files.createDirectories(otherPaths.repo(foreign));
		DockerSandbox other = new DockerSandbox(docker, otherPaths, new AgenticProperties.Sandbox(true, otherRoot, "", "none",
				"", "", DataSize.ofMegabytes(256), 1, 128, "", Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000, "", ""));
		try {
			other.start(foreign, new SandboxSpec(TestRepos.ALPINE, Map.of())).block();
			List<UUID> cleaned = new SandboxJanitor(docker, store, sandbox, checkout).sweep().collectList().block();
			assertThat(cleaned).contains(runId).doesNotContain(foreign);
			assertThat(other.exec(foreign, "true", Duration.ofSeconds(30)).block().succeeded()).isTrue();
		}
		finally {
			other.destroy(foreign).block();
		}
	}

	@Test
	void anExplicitRuntimeIsUsedForSandboxes() {
		UUID run = UUID.randomUUID();
		DockerSandbox withRuntime = new DockerSandbox(docker, paths, new AgenticProperties.Sandbox(true, tmp, "", "none",
				"", "", DataSize.ofMegabytes(256), 1, 128, "", Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000, "",
				"runc"));
		try {
			java.nio.file.Files.createDirectories(paths.repo(run));
			withRuntime.start(run, new SandboxSpec(TestRepos.ALPINE, Map.of())).block();
			String name = docker.listContainersCmd().withLabelFilter(Map.of(DockerSandbox.LABEL_RUN, run.toString())).exec()
					.getFirst().getNames()[0];
			assertThat(docker.inspectContainerCmd(name).exec().getHostConfig().getRuntime()).isEqualTo("runc");
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		finally {
			withRuntime.destroy(run).block();
		}
	}

	@Test
	void streamsOutputLinesWithAnEnvironmentOnlyThatCommandSees() {
		List<String> lines = sandbox.execLines(runId, "echo \"one $AGENTIC_TEST_SECRET\"; echo two >&2; printf 'three\\nfour'",
				Map.of("AGENTIC_TEST_SECRET", "s3cr3t"), Duration.ofSeconds(30)).collectList().block();
		assertThat(lines).containsExactly("one s3cr3t", "three", "four");
		assertThat(run("echo \"[$AGENTIC_TEST_SECRET]\"", Duration.ofSeconds(30)).output()).contains("[]");
		assertThat(docker.inspectContainerCmd(DockerSandbox.containerName(runId)).exec().getConfig().getEnv())
				.noneMatch(e -> e.contains("s3cr3t"));
	}

	@Test
	void aFailedStreamedCommandReportsItsExitAndErrorOutput() {
		assertThatThrownBy(() -> sandbox.execLines(runId, "echo partial; echo broken >&2; exit 3", Map.of(),
				Duration.ofSeconds(30)).collectList().block())
				.isInstanceOfSatisfying(io.agenticsdlc.core.workspace.CommandFailedException.class, e -> {
					assertThat(e.result().exitCode()).isEqualTo(3);
					assertThat(e.result().output()).contains("broken").doesNotContain("partial");
				});
	}

	/** Closing the attach stream alone would leave the command running; cancelling kills its whole process group. */
	@Test
	void cancellingAStreamedCommandKillsIt() throws Exception {
		String first = sandbox.execLines(runId, "sleep 301 & sleep 302 & echo started; wait", Map.of(),
				Duration.ofMinutes(5)).blockFirst(Duration.ofSeconds(30));
		assertThat(first).isEqualTo("started");
		String left = "";
		for (int i = 0; i < 50; i++) {
			left = run("ps -o args | grep -c '[s]leep 30[12]' || true", Duration.ofSeconds(30)).output().strip();
			if (left.equals("0")) {
				break;
			}
			Thread.sleep(200);
		}
		assertThat(left).as("sleeps still running").isEqualTo("0");
	}

	@Test
	void streamedCommandsTimeOut() {
		assertThatThrownBy(() -> sandbox.execLines(runId, "echo begun; sleep 30", Map.of(), Duration.ofSeconds(1))
				.collectList().block())
				.isInstanceOfSatisfying(io.agenticsdlc.core.workspace.CommandFailedException.class,
						e -> assertThat(e.result().timedOut()).isTrue());
	}

	@Test
	void linesSplitAcrossFramesAndMultiByteCharacters() {
		List<String> lines = new java.util.ArrayList<>();
		DockerSandbox.Lines splitter = new DockerSandbox.Lines(lines::add);
		byte[] text = "größe\r\nzwei\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		splitter.accept(java.util.Arrays.copyOfRange(text, 0, 3));
		splitter.accept(java.util.Arrays.copyOfRange(text, 3, text.length));
		splitter.accept("rest".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		splitter.flush();
		assertThat(lines).containsExactly("größe", "zwei", "rest");
	}

	/** An AI CLI on the host (or in a volume) is in every sandbox, read-only, whatever the image (ADR-0008). */
	@Test
	void toolsAreMountedReadOnly() throws Exception {
		Path tools = tmp.resolve("tools");
		Files.createDirectories(tools);
		Files.writeString(tools.resolve("hello-tool"), "#!/bin/sh\necho tool says hi\n");
		tools.resolve("hello-tool").toFile().setExecutable(true, false);
		UUID run = UUID.randomUUID();
		Files.createDirectories(paths.repo(run));
		DockerSandbox withTools = new DockerSandbox(docker, paths, settings("none", "", ""),
				new DockerSandbox.Tools("", tools.toString()));
		try {
			withTools.start(run, new SandboxSpec(TestRepos.ALPINE, Map.of())).block();
			CommandResult result = withTools.exec(run, DockerSandbox.TOOLS + "/hello-tool && touch " + DockerSandbox.TOOLS
					+ "/x", Duration.ofSeconds(30)).block();
			assertThat(result.output()).contains("tool says hi", "Read-only file system");
			assertThat(result.succeeded()).isFalse();
		}
		finally {
			withTools.destroy(run).block();
		}
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
			assertThat(paths.repo(proxied)).as("created by the app, not as root by Docker").isDirectory();
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
				DataSize.ofMegabytes(256), 1, 128, user, Duration.ofMinutes(1), Duration.ofMinutes(5), 2_000, "", "");
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
