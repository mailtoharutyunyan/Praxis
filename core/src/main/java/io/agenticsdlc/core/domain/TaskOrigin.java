package io.agenticsdlc.core.domain;

/** Where a task came from. Drives the default {@link Trust} and which adapter reports status back. */
public enum TaskOrigin {
	PROMPT,
	JIRA,
	GITHUB_ISSUE,
	GITLAB_ISSUE,
	AZURE_DEVOPS_WORK_ITEM,
	SLACK,
	/** Submitted by an AI client over MCP: it may relay text it read elsewhere, so it is untrusted. */
	MCP
}
