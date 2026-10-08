package io.agenticsdlc.core.eval;

import io.agenticsdlc.core.domain.RepositoryRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One historical task: the repository at the ref before the fix, the ticket text, and how to grade the result.
 *
 * @param ref branch or tag to start from (the parent of the merged fix)
 * @param hiddenFiles files added only for grading, typically the tests from the merged pull request; the agent never
 *        sees them
 * @param failToPass commands that failed before the fix and must pass after it
 * @param passToPass commands that passed before and must still pass (regression guard)
 */
public record EvalCase(String id, String title, String description, RepositoryRef repository, String ref,
		Map<String, String> hiddenFiles, List<String> failToPass, List<String> passToPass) {

	public EvalCase {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(repository, "repository");
		hiddenFiles = hiddenFiles == null ? Map.of() : Map.copyOf(hiddenFiles);
		failToPass = failToPass == null ? List.of() : List.copyOf(failToPass);
		passToPass = passToPass == null ? List.of() : List.copyOf(passToPass);
		if (failToPass.isEmpty() && passToPass.isEmpty()) {
			throw new IllegalArgumentException("case " + id + " needs at least one grading command");
		}
	}
}
