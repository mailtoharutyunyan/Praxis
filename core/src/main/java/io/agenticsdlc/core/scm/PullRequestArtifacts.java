package io.agenticsdlc.core.scm;

import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.ScmKind;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pull requests a run opened, read from its {@code pull-request} artifacts: the newest per repository (a run
 * with companion repositories has several, ADR-0006).
 */
public final class PullRequestArtifacts {

	/** A pull request and the commit last pushed to its branch. */
	public record Published(PullRequests.PullRequest pullRequest, String commit, String repositoryKey) {
	}

	private PullRequestArtifacts() {
	}

	/** @param primary the task's repository; its pull request gets a null {@code repository} */
	public static List<Published> latest(List<RunEvent> events, RepositoryRef primary) {
		Map<String, Published> byRepository = new LinkedHashMap<>();
		for (RunEvent event : events) {
			if (event.type() != RunEventType.ARTIFACT_PRODUCED || !PublishStage.PULL_REQUEST.equals(event.payload().get("kind"))) {
				continue;
			}
			Map<String, Object> p = event.payload();
			RepositoryRef repository = p.get("repository") == null ? primary
					: new RepositoryRef(p.get("scmKind") == null ? primary.kind() : ScmKind.valueOf(String.valueOf(p.get("scmKind"))),
							URI.create(String.valueOf(p.get("repository"))));
			boolean isPrimary = repository.key().equals(primary.key());
			byRepository.put(repository.key(), new Published(new PullRequests.PullRequest(String.valueOf(p.get("id")),
					String.valueOf(p.get("url")), isPrimary ? null : repository), String.valueOf(p.get("commit")),
					repository.key()));
		}
		return new ArrayList<>(byRepository.values());
	}
}
