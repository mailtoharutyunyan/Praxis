package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.slack.SlackClient;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.intake.TicketSyncStore;
import io.agenticsdlc.core.intake.TicketUpdates;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Slack as configured in the UI (ADR-0007): rebuilt when the connector changes, absent when skipped. */
public final class SlackRuntime {

	/** A configured Slack app: the client, thread updates, how commands are verified and the default target. */
	public record Active(Threads threads, TicketUpdates updates, String signingSecret, String defaultKind,
			String defaultRepository, String runLinkBase) {
	}

	/** Starts a thread in a channel; emits its reference, {@code <channel>:<ts>}. */
	@FunctionalInterface
	public interface Threads {
		Mono<String> start(String channel, String text);
	}

	private record Built(long version, Optional<Active> active) {
	}

	private final WebClient.Builder webClient;
	private final String apiUrl;
	private final ConnectorSettings connectors;
	private final RunStore store;
	private final TicketSyncStore cursors;
	private final Clock clock;
	private volatile Built built = new Built(Long.MIN_VALUE, Optional.empty());

	SlackRuntime(WebClient.Builder webClient, String apiUrl, ConnectorSettings connectors, RunStore store,
			TicketSyncStore cursors, Clock clock) {
		this.webClient = webClient;
		this.apiUrl = apiUrl;
		this.connectors = connectors;
		this.store = store;
		this.cursors = cursors;
		this.clock = clock;
	}

	public Optional<Active> current() {
		Built snapshot = built;
		long version = connectors.version();
		if (snapshot.version() != version) {
			snapshot = new Built(version, connectors.slack().map(slack -> {
				SlackClient client = new SlackClient(webClient, apiUrl, slack.botToken(), Duration.ofSeconds(10));
				String links = connectors.runLinkBase("");
				return new Active((channel, text) -> client.postMessage(channel, null, text), new TicketUpdates(store, client, cursors, TaskOrigin.SLACK, clock,
						Duration.ofDays(7), links), slack.signingSecret(), slack.defaultKind(), slack.defaultRepository(),
						links);
			}));
			built = snapshot;
		}
		return snapshot.active();
	}
}
