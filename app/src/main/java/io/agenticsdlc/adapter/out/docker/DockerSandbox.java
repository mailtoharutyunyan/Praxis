package io.agenticsdlc.adapter.out.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SandboxSpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * One long-lived container per run, named {@code agentic-run-<runId>}, with the run's checkout bind-mounted at
 * {@code /workspace}. Hardening: all Linux capabilities dropped, {@code no-new-privileges}, non-root user (refused
 * otherwise), memory, CPU and process limits, no network unless configured (never the host's), and no credentials in
 * the environment. Commands run via {@code docker exec}, wrapped in {@code timeout} so a hung build is killed inside
 * the container.
 */
public class DockerSandbox implements Sandbox {

	private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);
	static final String LABEL_RUN = "io.agenticsdlc.run";
	/** Keeps the container alive and exits promptly on SIGTERM; works in any image with a POSIX shell. */
	private static final String[] IDLE = { "sh", "-c", "trap 'exit 0' TERM; while :; do sleep 3600 & wait $!; done" };
	private static final String RUN_WITH_TIMEOUT = "if command -v timeout >/dev/null 2>&1; "
			+ "then exec timeout -s KILL \"$AGENTIC_TIMEOUT\" sh -c \"$AGENTIC_CMD\"; else exec sh -c \"$AGENTIC_CMD\"; fi";

	static final String MAVEN_SETTINGS = "/tmp/.agentic/maven-settings.xml";

	private final DockerClient docker;
	private final WorkspacePaths paths;
	private final AgenticProperties.Sandbox settings;
	private final URI proxy;
	private final String user;

	/** Fails fast on an unsafe configuration: host networking, or a sandbox that would run as root. */
	public DockerSandbox(DockerClient docker, WorkspacePaths paths, AgenticProperties.Sandbox settings) {
		this.docker = docker;
		this.paths = paths;
		this.settings = settings;
		if (settings.network().equals("host") || settings.network().startsWith("container:")) {
			throw new IllegalArgumentException("agentic.sandbox.network=" + settings.network()
					+ " would share the host's or another container's network; use none or an internal network");
		}
		this.proxy = settings.egressProxy().isBlank() ? null : URI.create(settings.egressProxy());
		if (proxy != null && (proxy.getHost() == null || proxy.getPort() < 0)) {
			throw new IllegalArgumentException("agentic.sandbox.egress-proxy needs a host and port, e.g. "
					+ "http://egress:3128");
		}
		if (Set.of("bridge", "default").contains(settings.network())) {
			log.warn("sandbox network '{}' allows unrestricted egress (cloud metadata, internal hosts); use an internal "
					+ "network with agentic.sandbox.egress-proxy for untrusted tasks", settings.network());
		}
		this.user = resolveUser();
	}

	static String containerName(UUID runId) {
		return "agentic-run-" + runId;
	}

	@Override
	public Mono<Void> start(UUID runId, SandboxSpec spec) {
		return Mono.<Void>fromRunnable(() -> startBlocking(runId, spec)).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<CommandResult> exec(UUID runId, String command, Duration timeout) {
		return Mono.fromCallable(() -> execBlocking(runId, command, timeout)).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<String> readFile(UUID runId, String relativePath, int maxBytes) {
		return Mono.fromCallable(() -> {
			Raw raw = execRaw(runId, READ_SCRIPT, List.of("AGENTIC_PATH=" + relativePath, "AGENTIC_MAX=" + maxBytes),
					maxBytes);
			return switch (raw.exitCode()) {
				case 0 -> new String(raw.stdout(), StandardCharsets.UTF_8);
				case 3 -> throw new NoSuchFileException(relativePath);
				case 4 -> throw new IllegalArgumentException(relativePath + " is not a regular file");
				case 5 -> throw new IllegalArgumentException(relativePath + " is larger than " + maxBytes
						+ " bytes; view a range or search instead");
				default -> throw new IllegalStateException("reading " + relativePath + " failed: " + raw.stderr());
			};
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> writeFile(UUID runId, String relativePath, String content) {
		return Mono.<Void>fromCallable(() -> {
			// Content travels base64-encoded in environment variables, in chunks well under the kernel's per-string
			// argument limit; each chunk is appended by the container user, so ownership and symlinks stay inside.
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			int offset = 0;
			boolean first = true;
			do {
				int length = Math.min(WRITE_CHUNK_BYTES, bytes.length - offset);
				String chunk = java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(bytes, offset,
						offset + length));
				Raw raw = execRaw(runId, first ? WRITE_FIRST_SCRIPT : APPEND_SCRIPT,
						List.of("AGENTIC_PATH=" + relativePath, "AGENTIC_DATA=" + chunk), 0);
				if (raw.exitCode() == 4) {
					throw new IllegalArgumentException(relativePath + " is a directory");
				}
				if (raw.exitCode() != 0) {
					throw new IllegalStateException("writing " + relativePath + " failed: " + raw.stderr());
				}
				offset += length;
				first = false;
			}
			while (offset < bytes.length);
			return null;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> destroy(UUID runId) {
		return Mono.<Void>fromRunnable(() -> {
			try {
				docker.removeContainerCmd(containerName(runId)).withForce(true).withRemoveVolumes(true).exec();
				log.info("removed sandbox for run {}", runId);
			}
			catch (NotFoundException e) {
				// already gone
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private void startBlocking(UUID runId, SandboxSpec spec) {
		String name = containerName(runId);
		InspectContainerResponse existing = inspect(name);
		if (existing != null && !spec.image().equals(existing.getConfig().getImage())) {
			log.info("sandbox image for run {} changed to {}, recreating", runId, spec.image());
			docker.removeContainerCmd(name).withForce(true).exec();
			existing = null;
		}
		if (existing == null) {
			pullIfMissing(spec.image());
			Path repo = paths.repo(runId);
			List<String> env = new ArrayList<>();
			env.add("HOME=/tmp");
			env.add("CI=true");
			if (proxy != null) {
				env.addAll(proxyEnvironment(proxy, settings.noProxy()));
			}
			spec.env().forEach((k, v) -> env.add(k + "=" + v));
			HostConfig host = HostConfig.newHostConfig()
					.withBinds(new Bind(repo.toString(), new Volume(Sandbox.WORKDIR)))
					.withCapDrop(Capability.values())
					.withSecurityOpts(List.of("no-new-privileges"))
					.withMemory(settings.memory().toBytes())
					.withMemorySwap(settings.memory().toBytes())
					.withNanoCPUs((long) (settings.cpus() * 1_000_000_000L))
					.withPidsLimit(settings.pidsLimit())
					.withNetworkMode(settings.network())
					.withInit(true);
			docker.createContainerCmd(spec.image())
					.withName(name)
					.withLabels(Map.of(LABEL_RUN, runId.toString()))
					.withEntrypoint(IDLE)
					.withCmd(List.of())
					.withWorkingDir(Sandbox.WORKDIR)
					.withUser(user)
					.withEnv(env)
					.withHostConfig(host)
					.exec();
			log.info("created sandbox {} from {}", name, spec.image());
		}
		InspectContainerResponse current = inspect(name);
		if (current != null && !Boolean.TRUE.equals(current.getState().getRunning())) {
			docker.startContainerCmd(name).exec();
		}
		if (proxy != null) {
			writeMavenSettings(runId);
		}
	}

	/** Proxy settings in the forms common build tools read; Maven only honours its settings file. */
	static List<String> proxyEnvironment(URI proxy, String noProxy) {
		String url = proxy.getScheme() + "://" + proxy.getHost() + ":" + proxy.getPort();
		String jvm = "-Dhttp.proxyHost=" + proxy.getHost() + " -Dhttp.proxyPort=" + proxy.getPort() + " -Dhttps.proxyHost="
				+ proxy.getHost() + " -Dhttps.proxyPort=" + proxy.getPort()
				+ (noProxy.isBlank() ? "" : " -Dhttp.nonProxyHosts=" + noProxy.replace(',', '|'));
		return List.of("HTTP_PROXY=" + url, "HTTPS_PROXY=" + url, "http_proxy=" + url, "https_proxy=" + url,
				"NO_PROXY=" + noProxy, "no_proxy=" + noProxy, "JAVA_TOOL_OPTIONS=" + jvm, "MAVEN_ARGS=-gs " + MAVEN_SETTINGS);
	}

	static String mavenSettings(URI proxy, String noProxy) {
		String host = xml(proxy.getHost());
		StringBuilder proxies = new StringBuilder();
		for (String protocol : List.of("http", "https")) {
			proxies.append("<proxy><id>agentic-").append(protocol).append("</id><active>true</active><protocol>")
					.append(protocol).append("</protocol><host>").append(host).append("</host><port>")
					.append(proxy.getPort()).append("</port><nonProxyHosts>").append(xml(noProxy.replace(',', '|')))
					.append("</nonProxyHosts></proxy>");
		}
		return "<settings><proxies>" + proxies + "</proxies></settings>\n";
	}

	private static String xml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void writeMavenSettings(UUID runId) {
		try {
			String data = java.util.Base64.getEncoder().encodeToString(
					mavenSettings(proxy, settings.noProxy()).getBytes(StandardCharsets.UTF_8));
			Raw raw = execRaw(runId, "mkdir -p /tmp/.agentic && printf '%s' \"$AGENTIC_DATA\" | base64 -d > " + MAVEN_SETTINGS,
					List.of("AGENTIC_DATA=" + data), 0);
			if (raw.exitCode() != 0) {
				throw new IllegalStateException("writing Maven proxy settings failed: " + raw.stderr());
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while configuring the sandbox", e);
		}
	}

	private CommandResult execBlocking(UUID runId, String command, Duration timeout) throws InterruptedException {
		long timeoutSeconds = Math.max(1, timeout.toSeconds());
		String execId = docker.execCreateCmd(containerName(runId))
				.withCmd("sh", "-c", RUN_WITH_TIMEOUT)
				.withEnv(List.of("AGENTIC_CMD=" + command, "AGENTIC_TIMEOUT=" + timeoutSeconds))
				.withWorkingDir(Sandbox.WORKDIR)
				.withAttachStdout(true)
				.withAttachStderr(true)
				.exec()
				.getId();
		BoundedOutput output = new BoundedOutput(settings.maxOutputChars());
		long started = System.nanoTime();
		boolean finished;
		try (ResultCallback.Adapter<Frame> callback = docker.execStartCmd(execId)
				.exec(new ResultCallback.Adapter<>() {
					@Override
					public void onNext(Frame frame) {
						output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
					}
				})) {
			// Grace period beyond the in-container timeout so its kill is observed rather than a client abort.
			finished = callback.awaitCompletion(timeoutSeconds + 15, TimeUnit.SECONDS);
		}
		catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		Duration took = Duration.ofNanos(System.nanoTime() - started);
		int exitCode = finished ? exitCode(execId) : -1;
		// 137 = SIGKILL from `timeout -s KILL`; only call it a timeout if the deadline really passed.
		boolean timedOut = !finished || (exitCode == 137 && took.compareTo(timeout) >= 0);
		return new CommandResult(command, exitCode, output.toString(), output.truncated(), timedOut, took);
	}

	/** Exit 3: missing, 4: not a regular file, 5: too large. Runs as the container user, inside /workspace. */
	private static final String READ_SCRIPT = "[ -e \"$AGENTIC_PATH\" ] || exit 3; [ -f \"$AGENTIC_PATH\" ] || exit 4; "
			+ "[ \"$(wc -c < \"$AGENTIC_PATH\")\" -le \"$AGENTIC_MAX\" ] || exit 5; exec cat -- \"$AGENTIC_PATH\"";
	static final int WRITE_CHUNK_BYTES = 48 * 1024;
	/** Creates parents and truncates; exit 4: the path is a directory. */
	private static final String WRITE_FIRST_SCRIPT = "[ -d \"$AGENTIC_PATH\" ] && exit 4; "
			+ "mkdir -p -- \"$(dirname -- \"$AGENTIC_PATH\")\" && printf '%s' \"$AGENTIC_DATA\" | base64 -d > \"$AGENTIC_PATH\"";
	private static final String APPEND_SCRIPT = "printf '%s' \"$AGENTIC_DATA\" | base64 -d >> \"$AGENTIC_PATH\"";

	private record Raw(int exitCode, byte[] stdout, String stderr) {
	}

	/** Exec with separate stdout (bytes, capped) and stderr. Used for file transfer. */
	private Raw execRaw(UUID runId, String script, List<String> env, int maxStdout)
			throws InterruptedException {
		String execId = docker.execCreateCmd(containerName(runId))
				.withCmd("sh", "-c", script)
				.withEnv(env)
				.withWorkingDir(Sandbox.WORKDIR)
				.withAttachStdout(true)
				.withAttachStderr(true)
				.exec()
				.getId();
		java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
		StringBuilder stderr = new StringBuilder();
		try (ResultCallback.Adapter<Frame> callback = docker.execStartCmd(execId).exec(new ResultCallback.Adapter<>() {
			@Override
			public void onNext(Frame frame) {
				if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR) {
					stderr.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
				}
				else if (maxStdout <= 0 || stdout.size() <= maxStdout) {
					stdout.writeBytes(frame.getPayload());
				}
			}
		})) {
			if (!callback.awaitCompletion(120, TimeUnit.SECONDS)) {
				throw new IllegalStateException("file transfer timed out");
			}
		}
		catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		return new Raw(exitCode(execId), stdout.toByteArray(), stderr.toString().strip());
	}

	/**
	 * The output stream can close a moment before Docker records the exit code, so wait briefly until the exec is
	 * reported as finished. -1 if it never is.
	 */
	private int exitCode(String execId) throws InterruptedException {
		for (int attempt = 0; attempt < 50; attempt++) {
			var inspect = docker.inspectExecCmd(execId).exec();
			if (!Boolean.TRUE.equals(inspect.isRunning()) && inspect.getExitCodeLong() != null) {
				return inspect.getExitCodeLong().intValue();
			}
			Thread.sleep(20);
		}
		return -1;
	}

	/** The non-root uid:gid sandboxes run as; scanners use it too. */
	String user() {
		return user;
	}

	/** Proxy environment for containers on the sandbox network; empty without an egress proxy. */
	List<String> proxyEnvironment() {
		return proxy == null ? List.of() : proxyEnvironment(proxy, settings.noProxy());
	}

	void pullIfMissing(String image) {
		try {
			docker.inspectImageCmd(image).exec();
		}
		catch (NotFoundException e) {
			log.info("pulling sandbox image {}", image);
			try {
				boolean done = docker.pullImageCmd(image).start()
						.awaitCompletion(settings.imagePullTimeout().toSeconds(), TimeUnit.SECONDS);
				if (!done) {
					throw new IllegalStateException("pulling " + image + " exceeded " + settings.imagePullTimeout());
				}
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while pulling " + image, interrupted);
			}
		}
	}

	private InspectContainerResponse inspect(String name) {
		try {
			return docker.inspectContainerCmd(name).exec();
		}
		catch (NotFoundException e) {
			return null;
		}
	}

	/**
	 * Configured user, else the owner of the workspace root so the container can write the bind mount. Root is refused:
	 * with the workspace bind-mounted, a root sandbox could plant root-owned files on the host.
	 */
	private String resolveUser() {
		String resolved;
		if (!settings.user().isBlank()) {
			resolved = settings.user().strip();
		}
		else {
			try {
				resolved = Files.getAttribute(paths.root(), "unix:uid") + ":" + Files.getAttribute(paths.root(), "unix:gid");
			}
			catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
				resolved = "1000:1000";
			}
		}
		String name = resolved.split(":", 2)[0];
		if (name.equals("0") || name.equals("root")) {
			throw new IllegalStateException("the sandbox would run as root (" + resolved + "); run the app as a non-root "
					+ "user that owns " + paths.root() + ", or set agentic.sandbox.user to a non-root uid:gid");
		}
		return resolved;
	}
}
