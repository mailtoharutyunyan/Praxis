package io.agenticsdlc.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Host layout of run workspaces, shared by the git and sandbox adapters. Only {@link #repo} is mounted into the
 * sandbox; {@link #gitDir} stays on the host so agent-written files can never become git hooks or config.
 */
public record WorkspacePaths(Path root) {

	public WorkspacePaths {
		root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
	}

	public Path runDir(UUID runId) {
		return root.resolve(runId.toString());
	}

	public Path repo(UUID runId) {
		return runDir(runId).resolve("repo");
	}

	public Path gitDir(UUID runId) {
		return runDir(runId).resolve("git");
	}
}
