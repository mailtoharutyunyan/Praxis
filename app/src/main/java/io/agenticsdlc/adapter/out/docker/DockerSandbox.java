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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * One long-lived container per run, named {@code agentic-run-<runId>}, with the run's checkout bind-mounted at
 * {@code /workspace}. Hardening: all Linux capabilities dropped, {@code no-new-privileges}, non-root user, memory,
 * CPU and process limits, and no credentials in the environment. Commands run via {@code docker exec}, wrapped in
 * {@code timeout} so a hung build is killed inside the container.
 */
public class DockerSandbox implements Sandbox {

	private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);
	static final String LABEL_RUN = "io.agenticsdlc.run";
	/** Keeps the container alive and exits promptly on SIGTERM; works in any image with a POSIX shell. */
	private static final String[] IDLE = { "sh", "-c", "trap 'exit 0' TERM; while :; do sleep 3600 & wait $!; done" };
	private static final String RUN_WITH_TIMEOUT = "if command -v timeout >/dev/null 2>&1; "
			+ "then exec timeout -s KILL \"$AGENTIC_TIMEOUT\" sh -c \"$AGENTIC_CMD\"; else exec sh -c \"$AGENTIC_CMD\"; fi";

	private final DockerClient docker;
	private final WorkspacePaths paths;
	private final AgenticProperties.Sandbox settings;

	public DockerSandbox(DockerClient docker, WorkspacePaths paths, AgenticProperties.Sandbox settings) {
		this.docker = docker;
		this.paths = paths;
		this.settings = settings;
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
					.withUser(user())
					.withEnv(env)
					.withHostConfig(host)
					.exec();
			log.info("created sandbox {} from {}", name, spec.image());
		}
		InspectContainerResponse current = inspect(name);
		if (current != null && !Boolean.TRUE.equals(current.getState().getRunning())) {
			docker.startContainerCmd(name).exec();
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
		Long exit = finished ? docker.inspectExecCmd(execId).exec().getExitCodeLong() : null;
		int exitCode = exit == null ? -1 : exit.intValue();
		// 137 = SIGKILL from `timeout -s KILL`; only call it a timeout if the deadline really passed.
		boolean timedOut = !finished || (exitCode == 137 && took.compareTo(timeout) >= 0);
		return new CommandResult(command, exitCode, output.toString(), output.truncated(), timedOut, took);
	}

	private void pullIfMissing(String image) {
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

	/** Configured user, else the owner of the workspace root so the container can write the bind mount. */
	private String user() {
		if (!settings.user().isBlank()) {
			return settings.user();
		}
		try {
			Object uid = Files.getAttribute(paths.root(), "unix:uid");
			Object gid = Files.getAttribute(paths.root(), "unix:gid");
			return uid + ":" + gid;
		}
		catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
			return "1000:1000";
		}
	}
}
