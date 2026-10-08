package io.agenticsdlc.core.domain;

/** Where a task came from. Drives the default {@link Trust} and which adapter reports status back. */
public enum TaskOrigin {
	PROMPT,
	JIRA,
	GITHUB_ISSUE,
	GITLAB_ISSUE,
	AZURE_DEVOPS_WORK_ITEM,
	SLACK
}
