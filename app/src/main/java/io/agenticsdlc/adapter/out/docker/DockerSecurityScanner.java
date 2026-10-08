package io.agenticsdlc.adapter.out.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.workspace.SecurityScanner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs gitleaks (secrets) and OSV-Scanner (vulnerable dependencies) on a run's working copy, each in a throwaway
 * container: the working copy mounted read-only at {@code /scan}, all capabilities dropped, {@code no-new-privileges},
 * the sandbox's non-root user, and no network for gitleaks. Secret values are redacted by gitleaks itself.
 */
public class DockerSecurityScanner implements SecurityScanner {

	private static final Logger log = LoggerFactory.getLogger(DockerSecurityScanner.class);
	static final String LABEL_SCAN = "io.agenticsdlc.scan";
	private static final String MOUNT = "/scan";
	/** Files whose change can bring in a dependency. */
	private static final Set<String> MANIFESTS = Set.of("pom.xml", "build.gradle", "build.gradle.kts",
			"gradle.lockfile", "package.json", "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock",
			"bun.lock", "requirements.txt", "poetry.lock", "pipfile.lock", "pyproject.toml", "uv.lock", "go.mod", "go.sum",
			"cargo.lock", "gemfile.lock", "composer.lock", "packages.lock.json", "pubspec.lock", "mix.lock");

	private final DockerClient docker;
	private final WorkspacePaths paths;
	private final DockerSandbox sandbox;
	private final AgenticProperties.Sandbox sandboxSettings;
	private final AgenticProperties.Scan settings;
	private final JsonMapper json;

	public DockerSecurityScanner(DockerClient docker, WorkspacePaths paths, DockerSandbox sandbox,
			AgenticProperties.Sandbox sandboxSettings, AgenticProperties.Scan settings, JsonMapper json) {
		this.docker = docker;
		this.paths = paths;
		this.sandbox = sandbox;
		this.sandboxSettings = sandboxSettings;
		this.settings = settings;
		this.json = json;
	}

	@Override
	public Mono<Report> scan(UUID runId, List<String> changedFiles) {
		return Mono.fromCallable(() -> scanBlocking(runId, Set.copyOf(changedFiles)))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private Report scanBlocking(UUID runId, Set<String> changed) throws InterruptedException {
		List<Finding> findings = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		if (changed.isEmpty()) {
			return Report.EMPTY;
		}
		if (settings.secrets()) {
			String out = run(runId, settings.secretsImage(), List.of("dir", MOUNT, "--no-banner", "--redact",
					"--report-format", "json", "--report-path", "-", "--exit-code", "0"), "none", List.of());
			findings.addAll(secrets(json.readTree(out.isBlank() ? "[]" : out), changed));
		}
		boolean manifestChanged = changed.stream().anyMatch(DockerSecurityScanner::isManifest);
		if (settings.dependencies() && manifestChanged) {
			if (sandboxSettings.network().equals("none")) {
				notes.add("Dependency scan skipped: dependency files changed, but the sandbox has no network "
						+ "(configure agentic.sandbox.egress-proxy and allow api.osv.dev).");
			}
			else {
				List<String> env = new ArrayList<>(sandbox.proxyEnvironment());
				env.add("HOME=/tmp");
				String out = run(runId, settings.dependenciesImage(), List.of("scan", "source", "-r", MOUNT, "--format",
						"json"), sandboxSettings.network(), env);
				findings.addAll(vulnerabilities(json.readTree(out.isBlank() ? "{}" : out), changed));
			}
		}
		return new Report(findings, notes);
	}

	/** Each gitleaks hit in a changed file blocks publishing. */
	static List<Finding> secrets(JsonNode report, Set<String> changed) {
		List<Finding> findings = new ArrayList<>();
		for (JsonNode hit : report) {
			String file = relative(hit.path("File").asString(""));
			if (changed.contains(file)) {
				findings.add(new Finding("gitleaks", "HIGH", file, hit.path("StartLine").asInt(0),
						hit.path("RuleID").asString("secret"), "possible secret: " + hit.path("Description").asString(""),
						true));
			}
		}
		return findings;
	}

	/** Vulnerable packages declared in changed manifests; advisory, rated by the highest CVSS score of each group. */
	static List<Finding> vulnerabilities(JsonNode report, Set<String> changed) {
		List<Finding> findings = new ArrayList<>();
		for (JsonNode result : report.path("results")) {
			String file = relative(result.path("source").path("path").asString(""));
			if (!changed.contains(file)) {
				continue;
			}
			for (JsonNode pkg : result.path("packages")) {
				String name = pkg.path("package").path("name").asString("?") + "@"
						+ pkg.path("package").path("version").asString("?");
				for (JsonNode group : pkg.path("groups")) {
					List<String> ids = group.path("ids").valueStream().map(JsonNode::asString).toList();
					findings.add(new Finding("osv-scanner", severity(group.path("max_severity").asString("")), file, 0,
							ids.isEmpty() ? "?" : ids.getFirst(), name + " has a known vulnerability " + ids, false));
				}
			}
		}
		return findings;
	}

	static String severity(String cvss) {
		try {
			double score = Double.parseDouble(cvss);
			return score >= 9 ? "CRITICAL" : score >= 7 ? "HIGH" : score >= 4 ? "MEDIUM" : "LOW";
		}
		catch (NumberFormatException e) {
			return "MEDIUM";
		}
	}

	static boolean isManifest(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
		return MANIFESTS.contains(name) || name.endsWith(".csproj") || name.matches("requirements.*\\.txt");
	}

	private static String relative(String path) {
		return path.startsWith(MOUNT + "/") ? path.substring(MOUNT.length() + 1) : path;
	}

	/** Runs one scanner container to completion and returns its stdout. */
	private String run(UUID runId, String image, List<String> command, String network, List<String> env)
			throws InterruptedException {
		sandbox.pullIfMissing(image);
		HostConfig host = HostConfig.newHostConfig()
				.withBinds(new Bind(paths.repo(runId).toString(), new Volume(MOUNT), com.github.dockerjava.api.model.AccessMode.ro))
				.withCapDrop(Capability.values())
				.withSecurityOpts(List.of("no-new-privileges"))
				.withMemory(1024L * 1024 * 1024)
				.withPidsLimit(256L)
				.withNetworkMode(network);
		String id = docker.createContainerCmd(image)
				.withCmd(command)
				.withUser(sandbox.user())
				.withEnv(env)
				.withLabels(Map.of(LABEL_SCAN, runId.toString()))
				.withHostConfig(host)
				.exec()
				.getId();
		try {
			docker.startContainerCmd(id).exec();
			Integer status = docker.waitContainerCmd(id).exec(new WaitContainerResultCallback())
					.awaitStatusCode(settings.timeout().toSeconds(), TimeUnit.SECONDS);
			ByteArrayOutputStream stdout = new ByteArrayOutputStream();
			StringBuilder stderr = new StringBuilder();
			try (ResultCallback.Adapter<Frame> logs = docker.logContainerCmd(id).withStdOut(true).withStdErr(true)
					.exec(new ResultCallback.Adapter<>() {
						@Override
						public void onNext(Frame frame) {
							if (frame.getStreamType() == StreamType.STDERR) {
								stderr.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
							}
							else {
								stdout.writeBytes(frame.getPayload());
							}
						}
					})) {
				logs.awaitCompletion(60, TimeUnit.SECONDS);
			}
			catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			// OSV-Scanner exits 1 when it finds vulnerabilities; anything else non-zero is a failure to scan.
			if (status == null || status > 1) {
				String tail = stderr.length() <= 500 ? stderr.toString() : stderr.substring(stderr.length() - 500);
				throw new IllegalStateException(image + " exited with " + status + ": " + tail.strip());
			}
			return stdout.toString(StandardCharsets.UTF_8);
		}
		finally {
			try {
				docker.removeContainerCmd(id).withForce(true).exec();
			}
			catch (NotFoundException e) {
				log.debug("scanner container {} already removed", id);
			}
		}
	}
}
