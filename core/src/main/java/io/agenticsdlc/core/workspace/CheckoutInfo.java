package io.agenticsdlc.core.workspace;

import java.util.Objects;
import java.util.Set;

/**
 * State of a run's working copy.
 *
 * @param rootEntries file and directory names at the repository root
 * @param files paths of the files a few directories deep, for detecting the services of a monorepo
 * @param projectConfig the repository's {@code .agentic-sdlc.yml}, if present and valid; null otherwise
 * @param agentInstructions contents of {@code AGENTS.md} or {@code CLAUDE.md} at the root, if present; null otherwise
 */
public record CheckoutInfo(String baseBranch, String baseCommit, String workBranch, Set<String> rootEntries,
		Set<String> files, ProjectConfig projectConfig, String agentInstructions) {

	public CheckoutInfo {
		Objects.requireNonNull(baseBranch, "baseBranch");
		Objects.requireNonNull(baseCommit, "baseCommit");
		Objects.requireNonNull(workBranch, "workBranch");
		rootEntries = Set.copyOf(rootEntries);
		files = Set.copyOf(files);
	}
}
