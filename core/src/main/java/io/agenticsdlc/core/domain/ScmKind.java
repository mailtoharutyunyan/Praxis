package io.agenticsdlc.core.domain;

/** Code hosting provider that receives the pushed branch and pull request. */
public enum ScmKind {
	GITHUB,
	GITLAB,
	BITBUCKET,
	AZURE_DEVOPS
}
