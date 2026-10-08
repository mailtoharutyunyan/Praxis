package io.agenticsdlc.core.domain;

/**
 * Whether task text can be treated as instructions from an authorised operator.
 * Untrusted text (tickets, issues, chat) may carry prompt injection, so it is always
 * shown to the model as data and forces a human gate before any external write.
 */
public enum Trust {
	TRUSTED,
	UNTRUSTED
}
