package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.jira.JiraTicketSystem;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.intake.TicketIntake;
import io.agenticsdlc.core.intake.TicketSyncStore;
import io.agenticsdlc.core.intake.TicketUpdates;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** Jira intake (webhook → run) and status comments back to the issue. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("agentic.jira.enabled")
class JiraConfiguration {

	@Bean
	JiraTicketSystem jiraTicketSystem(WebClient.Builder webClient, AgenticProperties properties) {
		AgenticProperties.Jira jira = properties.jira();
		if (jira.baseUrl().isBlank()) {
			throw new IllegalStateException("agentic.jira.base-url is required when agentic.jira.enabled=true");
		}
		if (jira.webhookSecret().isBlank() && jira.automationToken().isBlank()) {
			throw new IllegalStateException("set agentic.jira.webhook-secret or agentic.jira.automation-token");
		}
		return new JiraTicketSystem(webClient, jira.baseUrl(), jira.email(), jira.apiToken(), Duration.ofSeconds(30));
	}

	@Bean
	TicketIntake jiraIntake(JiraTicketSystem jira, TaskIntake intake, AgenticProperties properties) {
		Map<String, TicketIntake.ProjectTarget> projects = new LinkedHashMap<>();
		properties.jira().projects().forEach((key, project) -> projects.put(key, new TicketIntake.ProjectTarget(
				new RepositoryRef(project.kind(), project.cloneUrl()),
				project.baseBranch().isBlank() ? null : project.baseBranch())));
		return new TicketIntake(jira, intake, TaskOrigin.JIRA, properties.jira().triggerLabel(), projects);
	}

	@Bean
	TicketUpdates jiraUpdates(RunStore store, JiraTicketSystem jira, TicketSyncStore cursors, Clock clock,
			AgenticProperties properties) {
		return new TicketUpdates(store, jira, cursors, TaskOrigin.JIRA, clock, Duration.ofDays(7),
				properties.jira().runLinkBase());
	}

	@Bean
	TicketUpdatesWatcher jiraUpdatesWatcher(TicketUpdates updates, AgenticProperties properties) {
		return new TicketUpdatesWatcher(updates, properties.jira().updateInterval());
	}

	/** Posts pending status comments on a fixed interval. */
	static final class TicketUpdatesWatcher implements SmartLifecycle {

		private static final Logger log = LoggerFactory.getLogger(TicketUpdatesWatcher.class);

		private final TicketUpdates updates;
		private final Duration interval;
		private volatile Disposable schedule;

		TicketUpdatesWatcher(TicketUpdates updates, Duration interval) {
			this.updates = updates;
			this.interval = interval;
		}

		@Override
		public void start() {
			schedule = Flux.interval(interval, interval)
					.concatMap(tick -> updates.sweep().onErrorResume(e -> {
						log.warn("ticket update sweep failed", e);
						return Flux.empty();
					}))
					.subscribe();
		}

		@Override
		public void stop() {
			Disposable current = schedule;
			if (current != null) {
				current.dispose();
			}
			schedule = null;
		}

		@Override
		public boolean isRunning() {
			return schedule != null && !schedule.isDisposed();
		}
	}
}
