package io.agenticsdlc.core.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The services of a run's working copy as agents address them: by name, mapped to a sandbox environment. */
public interface Environments {

	Environments NONE = new Environments() {
		@Override
		public Optional<Target> target(UUID runId, String service) {
			return Optional.empty();
		}

		@Override
		public List<String> services(UUID runId) {
			return List.of();
		}
	};

	/** Where a service's commands run: its sandbox environment and directory. */
	record Target(String environment, BuildPlan.Component component) {
	}

	Optional<Target> target(UUID runId, String service);

	/** Service names of the run, as known on this node; empty before its workspace was prepared here. */
	List<String> services(UUID runId);
}
