package io.agenticsdlc.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import io.agenticsdlc.adapter.out.docker.DockerSandbox;
import io.agenticsdlc.adapter.out.git.JGitRepositoryCheckout;
import io.agenticsdlc.core.stage.PrepareContextStage;
import io.agenticsdlc.core.stage.RunWorkspace;
import io.agenticsdlc.core.stage.VerifyStage;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

	@Bean
	RepositoryCheckout repositoryCheckout(WorkspacePaths paths, AgenticProperties properties) {
		AgenticProperties.Scm scm = properties.scm();
		return new JGitRepositoryCheckout(paths, scm.tokens(), scm.mirrors(), scm.cloneDepth());
	}

	@Bean
	Sandbox sandbox(DockerClient docker, WorkspacePaths paths, AgenticProperties properties) {
		return new DockerSandbox(docker, paths, properties.sandbox());
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
	VerifyStage verifyStage(RunWorkspace workspace) {
		return new VerifyStage(workspace);
	}
}
