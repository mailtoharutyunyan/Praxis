package io.agenticsdlc.config;

import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.intake.TicketSyncStore;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/** Jira intake (webhook → run) and status comments back to the issue, from properties or the UI (ADR-0007). */
@Configuration(proxyBeanMethods = false)
class JiraConfiguration {

	@Bean
	JiraRuntime jiraRuntime(WebClient.Builder webClient, AgenticProperties properties, ConnectorSettings connectors,
			TaskIntake intake, RunStore store, TicketSyncStore cursors, Clock clock) {
		return new JiraRuntime(webClient, properties, connectors, intake, store, cursors, clock);
	}

	/** Posts pending status comments on a fixed interval, from one instance at a time (no duplicate comments). */
	@Bean
	PeriodicJob jiraUpdatesWatcher(JiraRuntime jira, AgenticProperties properties,
			ClusterConfiguration.JobLeases leases) {
		return new PeriodicJob("Jira status comments", properties.jira().updateInterval(), Duration.ofMinutes(10),
				() -> jira.current().map(active -> (Flux<?>) active.updates().sweep()).orElse(Flux.empty()),
				leases.forJob("jira-ticket-updates"));
	}
}
