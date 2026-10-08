package io.agenticsdlc.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import io.agenticsdlc.adapter.out.docker.DockerSandbox;
import io.agenticsdlc.adapter.out.docker.DockerSecurityScanner;
import io.agenticsdlc.adapter.out.docker.SandboxJanitor;
import io.agenticsdlc.adapter.out.git.JGitRepositoryCheckout;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.stage.PrepareContextStage;
import io.agenticsdlc.core.stage.RunWorkspace;
import io.agenticsdlc.core.stage.VerifyStage;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SecurityScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Real workspace stages: JGit checkout on the host plus a hardened Docker sandbox (ADR-0003). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("agentic.sandbox.enabled")
class WorkspaceConfiguration {

	@Bean
	WorkspacePaths workspacePaths(AgenticProperties properties) throws IOException {
		WorkspacePaths paths = new WorkspacePaths(properties.sandbox().workspaceRoot());
		Files.createDirectories(paths.root());
		return paths;
	}

	@Bean(destroyMethod = "close")
	DockerClient dockerClient(AgenticProperties properties) {
		DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
		if (!properties.sandbox().dockerHost().isBlank()) {
			builder.withDockerHost(properties.sandbox().dockerHost());
		}
		DockerClientConfig config = builder.build();
		ZerodepDockerHttpClient http = new ZerodepDockerHttpClient.Builder()
				.dockerHost(config.getDockerHost())
				.sslConfig(config.getSSLConfig())
				.maxConnections(64)
				.connectionTimeout(Duration.ofSeconds(10))
				.build();
		return DockerClientImpl.getInstance(config, http);
	}

	/** Also the {@code ChangePublisher}: the same host-side git setup clones and pushes. */
	@Bean
	JGitRepositoryCheckout repositoryCheckout(WorkspacePaths paths, AgenticProperties properties,
			io.agenticsdlc.config.connectors.ConnectorSettings connectors) {
		AgenticProperties.Scm scm = properties.scm();
		return new JGitRepositoryCheckout(paths, connectors::scmToken, scm.mirrors(), scm.cloneDepth(), scm.authorName(),
				scm.authorEmail());
	}

	@Bean
	DockerSandbox sandbox(DockerClient docker, WorkspacePaths paths, AgenticProperties properties) {
		return new DockerSandbox(docker, paths, properties.sandbox());
	}

	@Bean
	SecurityScanner securityScanner(DockerClient docker, WorkspacePaths paths, DockerSandbox sandbox,
			AgenticProperties properties, JsonMapper json) {
		return new DockerSecurityScanner(docker, paths, sandbox, properties.sandbox(), properties.scan(), json);
	}

	@Bean
	RunWorkspace runWorkspace(RepositoryCheckout checkout, Sandbox sandbox, AgenticProperties properties) {
		return new RunWorkspace(checkout, sandbox, properties.sandbox().commandTimeout());
	}

	@Bean
	PrepareContextStage prepareContextStage(RunWorkspace workspace) {
		return new PrepareContextStage(workspace);
	}

	@Bean
	VerifyStage verifyStage(RunWorkspace workspace, SecurityScanner scanner) {
		return new VerifyStage(workspace, scanner);
	}

	@Bean
	SandboxJanitor sandboxJanitor(DockerClient docker, RunStore store, Sandbox sandbox, RepositoryCheckout checkout) {
		return new SandboxJanitor(docker, store, sandbox, checkout);
	}

	/** Per node: each instance cleans the sandboxes and workspaces on its own Docker host and disk. */
	@Bean
	PeriodicJob sandboxJanitorJob(SandboxJanitor janitor) {
		return new PeriodicJob("sandbox cleanup", Duration.ofMinutes(5), Duration.ofMinutes(10), janitor::sweep, null);
	}
}
