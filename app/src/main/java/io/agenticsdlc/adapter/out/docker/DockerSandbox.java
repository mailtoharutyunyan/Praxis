package io.agenticsdlc.adapter.out.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.VolumeOptions;
import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.workspace.CommandFailedException;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.ProjectConfig;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
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
	static final String LABEL_ENVIRONMENT = "io.agenticsdlc.environment";
	static final String LABEL_SIDECAR = "io.agenticsdlc.sidecar";
	/** Which installation a container belongs to, so cleanup never touches another one's on a shared Docker host. */
	static final String LABEL_WORKSPACE = "io.agenticsdlc.workspace";
	/** Keeps the container alive and exits promptly on SIGTERM; works in any image with a POSIX shell. */
	private static final String[] IDLE = { "sh", "-c", "trap 'exit 0' TERM; while :; do sleep 3600 & wait $!; done" };
	private static final String RUN_WITH_TIMEOUT = "if command -v timeout >/dev/null 2>&1; "
			+ "then exec timeout -s KILL \"$AGENTIC_TIMEOUT\" sh -c \"$AGENTIC_CMD\"; else exec sh -c \"$AGENTIC_CMD\"; fi";

	static final String MAVEN_SETTINGS = "/tmp/.agentic/maven-settings.xml";

	/** Where {@link Tools} are mounted, read-only, in every environment of a run. */
	public static final String TOOLS = "/opt/agentic-tools";

	/**
	 * Programs mounted read-only into every sandbox, whatever its image, such as an AI CLI (ADR-0008): from a Docker
	 * volume, else from a directory on the Docker host; empty strings for neither.
	 */
	public record Tools(String volume, String hostDir) {
		public static final Tools NONE = new Tools("", "");
	}

	private final DockerClient docker;
	private final WorkspacePaths paths;
	private final AgenticProperties.Sandbox settings;
	private final WorkspaceMounts mounts;
	private final URI proxy;
	private final String user;

	/** Fails fast on an unsafe configuration: host networking, or a sandbox that would run as root. */
	public DockerSandbox(DockerClient docker, WorkspacePaths paths, AgenticProperties.Sandbox settings) {
		this.docker = docker;
		this.paths = paths;
		this.settings = settings;
		this.mounts = new WorkspaceMounts(paths.root(), settings.workspaceVolume());
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

	private Tools tools = Tools.NONE;

	/** @param tools mounted read-only at {@value #TOOLS} in every environment */
	public DockerSandbox(DockerClient docker, WorkspacePaths paths, AgenticProperties.Sandbox settings, Tools tools) {
		this(docker, paths, settings);
		this.tools = Objects.requireNonNull(tools, "tools");
	}

	static String containerName(UUID runId) {
		return "agentic-run-" + runId;
	}

	/** The main environment keeps the plain name; services' environments add theirs (ADR-0006). */
	static String containerName(UUID runId, String environment) {
		return environment.equals(SandboxSpec.MAIN) ? containerName(runId) : containerName(runId) + "-" + environment;
	}

	/** Per-run internal network for sidecars: no route out, shared only by the run's own containers. */
	static String networkName(UUID runId) {
		return containerName(runId);
	}

	static String sidecarName(UUID runId, String sidecar) {
		return containerName(runId) + "-sidecar-" + sidecar;
	}

	@Override
	public Mono<Void> start(UUID runId, SandboxSpec spec) {
		return Mono.<Void>fromRunnable(() -> startBlocking(runId, spec)).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<CommandResult> exec(UUID runId, String command, Duration timeout) {
		return exec(runId, SandboxSpec.MAIN, command, timeout);
	}

	@Override
	public Mono<CommandResult> exec(UUID runId, String environment, String command, Duration timeout) {
		return Mono.fromCallable(() -> execBlocking(containerName(runId, environment), command, timeout, Sandbox.WORKDIR))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * Standard output is decoded as UTF-8 and split into lines as frames arrive; standard error is kept (its tail) for
	 * the failure. The command records its process id first, so that cancelling can kill it, with the processes it
	 * started, from a second exec: closing the attach stream alone would leave it running in the container.
	 */
	@Override
	public Flux<String> execLines(UUID runId, String command, Map<String, String> env, Duration timeout) {
		return execLines(runId, SandboxSpec.MAIN, command, env, timeout);
	}

	@Override
	public Flux<String> execLines(UUID runId, String environment, String command, Map<String, String> env,
			Duration timeout) {
		String container = containerName(runId, environment);
		return Flux.<String>create(sink -> {
			String pidFile = "/tmp/.agentic/exec-" + UUID.randomUUID() + ".pid";
			long seconds = Math.max(1, timeout.toSeconds());
			List<String> variables = new ArrayList<>();
			env.forEach((name, value) -> variables.add(name + "=" + value));
			variables.addAll(List.of("AGENTIC_CMD=" + command, "AGENTIC_TIMEOUT=" + seconds, "AGENTIC_PIDFILE=" + pidFile));
			String execId = docker.execCreateCmd(container)
					.withCmd("sh", "-c", RECORD_PID + RUN_WITH_TIMEOUT)
					.withEnv(variables)
					.withWorkingDir(Sandbox.WORKDIR)
					.withAttachStdout(true)
					.withAttachStderr(true)
					.exec()
					.getId();
			Lines lines = new Lines(sink::next);
			BoundedOutput stderr = new BoundedOutput(4_000);
			long started = System.nanoTime();
			AtomicBoolean ended = new AtomicBoolean();
			ResultCallback.Adapter<Frame> callback = docker.execStartCmd(execId).exec(new ResultCallback.Adapter<>() {
				@Override
				public void onNext(Frame frame) {
					if (frame.getStreamType() == StreamType.STDERR) {
						stderr.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
					}
					else {
						lines.accept(frame.getPayload());
					}
				}

				@Override
				public void onError(Throwable error) {
					ended.set(true);
					super.onError(error);
					sink.error(error);
				}

				@Override
				public void onComplete() {
					ended.set(true);
					super.onComplete();
					lines.flush();
					Schedulers.boundedElastic().schedule(() -> {
						try {
							Duration took = Duration.ofNanos(System.nanoTime() - started);
							int exitCode = exitCode(execId);
							if (exitCode == 0) {
								sink.complete();
								return;
							}
							boolean timedOut = exitCode == 137 && took.compareTo(timeout) >= 0;
							sink.error(new CommandFailedException(new CommandResult(command, exitCode, stderr.toString(),
									stderr.truncated(), timedOut, took)));
						}
						catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							sink.error(e);
						}
					});
				}
			});
			sink.onDispose(() -> {
				if (!ended.get()) {
					Schedulers.boundedElastic().schedule(() -> kill(container, pidFile));
				}
				try {
					callback.close();
				}
				catch (IOException e) {
					log.debug("closing the output of an exec in {} failed: {}", container, e.getMessage());
				}
			});
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/** Records the shell's pid, which {@code exec} hands on: the command's own, and its process group's id. */
	private static final String RECORD_PID = "mkdir -p /tmp/.agentic && echo $$ > \"$AGENTIC_PIDFILE\"; ";
	/** TERM to the command's process group (else the process), then KILL if it is still there after two seconds. */
	private static final String KILL_SCRIPT = "p=$(cat \"$AGENTIC_PIDFILE\" 2>/dev/null) || exit 0; "
			+ "kill -TERM -- -\"$p\" 2>/dev/null || kill -TERM \"$p\" 2>/dev/null; i=0; "
			+ "while [ $i -lt 20 ] && kill -0 \"$p\" 2>/dev/null; do sleep 0.1; i=$((i+1)); done; "
			+ "kill -KILL -- -\"$p\" 2>/dev/null || kill -KILL \"$p\" 2>/dev/null; rm -f \"$AGENTIC_PIDFILE\"";

	private void kill(String container, String pidFile) {
		try {
			execRaw(container, KILL_SCRIPT, List.of("AGENTIC_PIDFILE=" + pidFile), 0);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		catch (RuntimeException e) {
			log.warn("could not stop a cancelled command in {}: {}", container, e.getMessage());
		}
	}

	static final int MAX_LINE_BYTES = 4 * 1024 * 1024;

	/** Splits a byte stream into UTF-8 lines without their line break; a line over {@link #MAX_LINE_BYTES} is dropped. */
	static final class Lines {

		private final java.util.function.Consumer<String> out;
		private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
		private boolean overlong;

		Lines(java.util.function.Consumer<String> out) {
			this.out = out;
		}

		synchronized void accept(byte[] bytes) {
			for (byte b : bytes) {
				if (b == '\n') {
					emit();
				}
				else if (line.size() < MAX_LINE_BYTES) {
					line.write(b);
				}
				else {
					overlong = true;
				}
			}
		}

		/** The last line, if the output did not end with a line break. */
		synchronized void flush() {
			if (line.size() > 0 || overlong) {
				emit();
			}
		}

		private void emit() {
			if (overlong) {
				log.warn("dropped an output line longer than {} bytes", MAX_LINE_BYTES);
			}
			else {
				String text = line.toString(StandardCharsets.UTF_8);
				out.accept(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text);
			}
			line.reset();
			overlong = false;
		}
	}

	/**
	 * Sidecars (databases, brokers) on the run's internal network, reachable from its environments by name. They do
	 * not mount the workspace and get no credentials; they keep their image's default capabilities, which database
	 * entrypoints need, with {@code no-new-privileges} and the sandbox's resource limits.
	 */
	@Override
	public Mono<Void> startSidecars(UUID runId, List<ProjectConfig.SidecarConfig> sidecars) {
		if (sidecars.isEmpty()) {
			return Mono.empty();
		}
		return Mono.<Void>fromCallable(() -> {
			ensureNetwork(runId);
			for (ProjectConfig.SidecarConfig sidecar : sidecars) {
				startSidecar(runId, sidecar);
			}
			for (ProjectConfig.SidecarConfig sidecar : sidecars) {
				awaitReady(runId, sidecar);
			}
			return null;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private void ensureNetwork(UUID runId) {
		String network = networkName(runId);
		try {
			docker.inspectNetworkCmd().withNetworkId(network).exec();
		}
		catch (NotFoundException e) {
			docker.createNetworkCmd().withName(network).withInternal(true)
					.withLabels(Map.of(LABEL_RUN, runId.toString())).exec();
			log.info("created internal network {}", network);
		}
	}

	private boolean hasNetwork(UUID runId) {
		try {
			docker.inspectNetworkCmd().withNetworkId(networkName(runId)).exec();
			return true;
		}
		catch (NotFoundException e) {
			return false;
		}
	}

	private void startSidecar(UUID runId, ProjectConfig.SidecarConfig sidecar) {
		if (!sidecar.name().matches("[a-z0-9][a-z0-9-]{0,29}")) {
			throw new IllegalArgumentException("sidecar name must be lower case letters, digits and dashes: " + sidecar.name());
		}
		String name = sidecarName(runId, sidecar.name());
		InspectContainerResponse existing = inspect(name);
		if (existing != null && !sidecar.image().equals(existing.getConfig().getImage())) {
			docker.removeContainerCmd(name).withForce(true).exec();
			existing = null;
		}
		if (existing == null) {
			pullIfMissing(sidecar.image());
			List<String> env = new ArrayList<>();
			sidecar.env().forEach((k, v) -> env.add(k + "=" + v));
			HostConfig host = hostConfig()
					.withSecurityOpts(List.of("no-new-privileges"))
					.withMemory(settings.memory().toBytes())
					.withMemorySwap(settings.memory().toBytes())
					.withNanoCPUs((long) (settings.cpus() * 1_000_000_000L))
					.withPidsLimit(settings.pidsLimit())
					.withNetworkMode(networkName(runId))
					.withInit(true);
			docker.createContainerCmd(sidecar.image())
					.withName(name)
					.withLabels(Map.of(LABEL_RUN, runId.toString(), LABEL_SIDECAR, sidecar.name(), LABEL_WORKSPACE,
							mounts.identity()))
					.withEnv(env)
					.withAliases(sidecar.name())
					.withHostConfig(host)
					.exec();
			log.info("created sidecar {} from {}", name, sidecar.image());
		}
		InspectContainerResponse current = inspect(name);
		if (current != null && !Boolean.TRUE.equals(current.getState().getRunning())) {
			docker.startContainerCmd(name).exec();
		}
	}

	/** Runs the sidecar's readiness command until it succeeds; fails the stage after {@code imagePullTimeout}. */
	private void awaitReady(UUID runId, ProjectConfig.SidecarConfig sidecar) throws InterruptedException {
		if (sidecar.ready() == null || sidecar.ready().isBlank()) {
			return;
		}
		long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
		CommandResult last = null;
		while (System.nanoTime() < deadline) {
			last = execBlocking(sidecarName(runId, sidecar.name()), sidecar.ready(), Duration.ofSeconds(10), null);
			if (last.succeeded()) {
				return;
			}
			Thread.sleep(1_000);
		}
		throw new IllegalStateException("sidecar " + sidecar.name() + " did not become ready: `" + sidecar.ready()
				+ "` last said " + (last == null ? "nothing" : last.tail(500)));
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

	/** Removes every container of the run (environments and sidecars) and its network. */
	@Override
	public Mono<Void> destroy(UUID runId) {
		return Mono.<Void>fromRunnable(() -> {
			List<String> containers = docker.listContainersCmd().withShowAll(true)
					.withLabelFilter(Map.of(LABEL_RUN, runId.toString())).exec().stream()
					.map(com.github.dockerjava.api.model.Container::getId).toList();
			for (String container : containers) {
				try {
					docker.removeContainerCmd(container).withForce(true).withRemoveVolumes(true).exec();
				}
				catch (NotFoundException e) {
					// already gone
				}
			}
			try {
				docker.removeNetworkCmd(networkName(runId)).exec();
			}
			catch (NotFoundException e) {
				// no sidecars
			}
			if (!containers.isEmpty()) {
				log.info("removed sandbox for run {} ({} containers)", runId, containers.size());
			}
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private void startBlocking(UUID runId, SandboxSpec spec) {
		String name = containerName(runId, spec.name());
		boolean sidecars = hasNetwork(runId);
		InspectContainerResponse existing = inspect(name);
		if (existing != null && !spec.image().equals(existing.getConfig().getImage())) {
			log.info("sandbox image for run {} changed to {}, recreating", runId, spec.image());
			docker.removeContainerCmd(name).withForce(true).exec();
			existing = null;
		}
		if (existing == null) {
			pullIfMissing(spec.image());
			Path repo = paths.repo(runId);
			if (!mounts.usesVolume()) {
				// Docker would create a missing bind source as root, which the app could then not clean up.
				try {
					java.nio.file.Files.createDirectories(repo);
				}
				catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException("cannot create the workspace " + repo, e);
				}
			}
			List<String> env = new ArrayList<>();
			env.add("HOME=/tmp");
			env.add("CI=true");
			if (proxy != null) {
				env.addAll(proxyEnvironment(proxy, settings.noProxy()));
			}
			spec.env().forEach((k, v) -> env.add(k + "=" + v));
			HostConfig host = mounts.mount(hostConfig(), repo, Sandbox.WORKDIR, false)
					.withCapDrop(Capability.values())
					.withSecurityOpts(List.of("no-new-privileges"))
					.withMemory(settings.memory().toBytes())
					.withMemorySwap(settings.memory().toBytes())
					.withNanoCPUs((long) (settings.cpus() * 1_000_000_000L))
					.withPidsLimit(settings.pidsLimit())
					// With sidecars and no network configured, the run's internal network is the only one.
					.withNetworkMode(sidecars && settings.network().equals("none") ? networkName(runId) : settings.network())
					.withInit(true);
			docker.createContainerCmd(spec.image())
					.withName(name)
					.withLabels(Map.of(LABEL_RUN, runId.toString(), LABEL_ENVIRONMENT, spec.name(), LABEL_WORKSPACE,
							mounts.identity()))
					.withEntrypoint(IDLE)
					.withCmd(List.of())
					.withWorkingDir(Sandbox.WORKDIR)
					.withUser(user)
					.withEnv(env)
					.withHostConfig(withTools(host))
					.exec();
			log.info("created sandbox {} from {}", name, spec.image());
		}
		InspectContainerResponse current = inspect(name);
		if (sidecars && current != null && !settings.network().equals("none")
				&& !current.getNetworkSettings().getNetworks().containsKey(networkName(runId))) {
			docker.connectToNetworkCmd().withNetworkId(networkName(runId)).withContainerId(name).exec();
		}
		if (current != null && !Boolean.TRUE.equals(current.getState().getRunning())) {
			docker.startContainerCmd(name).exec();
		}
		if (proxy != null) {
			writeMavenSettings(name);
		}
	}

	/** Adds the read-only tools mount, if any; a volume is mounted as it is, never seeded from the image. */
	private HostConfig withTools(HostConfig host) {
		Mount mount;
		if (!tools.volume().isBlank()) {
			mount = new Mount().withType(MountType.VOLUME).withSource(tools.volume())
					.withVolumeOptions(new VolumeOptions().withNoCopy(true));
		}
		else if (!tools.hostDir().isBlank()) {
			mount = new Mount().withType(MountType.BIND).withSource(tools.hostDir());
		}
		else {
			return host;
		}
		List<Mount> all = new ArrayList<>(host.getMounts() == null ? List.of() : host.getMounts());
		all.add(mount.withTarget(TOOLS).withReadOnly(true));
		return host.withMounts(all);
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

	private void writeMavenSettings(String container) {
		try {
			String data = java.util.Base64.getEncoder().encodeToString(
					mavenSettings(proxy, settings.noProxy()).getBytes(StandardCharsets.UTF_8));
			Raw raw = execRaw(container, "mkdir -p /tmp/.agentic && printf '%s' \"$AGENTIC_DATA\" | base64 -d > " + MAVEN_SETTINGS,
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

	/** @param workingDir null for the image's own (sidecars have no workspace) */
	private CommandResult execBlocking(String container, String command, Duration timeout, String workingDir)
			throws InterruptedException {
		long timeoutSeconds = Math.max(1, timeout.toSeconds());
		String execId = docker.execCreateCmd(container)
				.withCmd("sh", "-c", RUN_WITH_TIMEOUT)
				.withEnv(List.of("AGENTIC_CMD=" + command, "AGENTIC_TIMEOUT=" + timeoutSeconds))
				.withWorkingDir(workingDir)
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
	private Raw execRaw(UUID runId, String script, List<String> env, int maxStdout) throws InterruptedException {
		return execRaw(containerName(runId), script, env, maxStdout);
	}

	private Raw execRaw(String container, String script, List<String> env, int maxStdout)
			throws InterruptedException {
		String execId = docker.execCreateCmd(container)
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

	/** A host config with the configured container runtime (e.g. gVisor's {@code runsc}); scanners use it too. */
	HostConfig hostConfig() {
		HostConfig host = HostConfig.newHostConfig();
		return settings.runtime().isBlank() ? host : host.withRuntime(settings.runtime());
	}

	/** How workspace directories are mounted; scanners use it too. */
	WorkspaceMounts mounts() {
		return mounts;
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
