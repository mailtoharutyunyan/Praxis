package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.TaskOrigin;

/**
 * A request to start work, as received from an intake adapter. Field validation happens when the
 * {@link io.agenticsdlc.core.domain.Task} is built.
 *
 * @param requestedBy authenticated subject (prompt API) or the source's user reference (Jira, issues)
 * @param idempotencyKey optional client key that makes retries of the same submission safe
 */
public record NewTask(TaskOrigin origin, String externalRef, String title, String description, RepositoryRef repository,
		String baseBranch, String requestedBy, String idempotencyKey) {
}
