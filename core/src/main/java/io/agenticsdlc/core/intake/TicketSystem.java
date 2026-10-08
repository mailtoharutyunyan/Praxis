package io.agenticsdlc.core.intake;

import reactor.core.publisher.Mono;

/**
 * A ticket system the service reads tasks from and reports progress to (Jira first). Writes here are deterministic
 * status comments made by the orchestrator, never by an agent (ADR-0003).
 */
public interface TicketSystem {

	Mono<Ticket> fetch(String key);

	/** Post a plain-text comment, optionally ending with a link. */
	Mono<Void> comment(String key, String text, String link);
}
