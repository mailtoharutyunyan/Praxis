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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

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
		return new JiraTicketSystem(webClient, jira.baseUrl(), jira.email(), jira.apiToken(), Duration.ofSeconds(30),
				!"data-center".equals(jira.deployment()));
	}

	@Bean
	TicketIntake jiraIntake(JiraTicketSystem jira, TaskIntake intake, AgenticProperties properties) {
		Map<String, TicketIntake.ProjectTarget> projects = new LinkedHashMap<>();
		properties.jira().projects().forEach((key, project) -> projects.put(key, new TicketIntake.ProjectTarget(
				new RepositoryRef(project.kind(), project.cloneUrl()),
				project.baseBranch().isBlank() ? null : project.baseBranch(),
				project.companions().stream().map(c -> {
					RepositoryRef ref = new RepositoryRef(c.kind() == null ? project.kind() : c.kind(), c.cloneUrl());
					return new io.agenticsdlc.core.domain.Companion(c.alias().isBlank()
							? io.agenticsdlc.core.domain.Companion.aliasFor(ref) : c.alias(), ref,
							c.baseBranch().isBlank() ? null : c.baseBranch());
				}).toList())));
		return new TicketIntake(jira, intake, TaskOrigin.JIRA, properties.jira().triggerLabel(), projects);
	}

	@Bean
	TicketUpdates jiraUpdates(RunStore store, JiraTicketSystem jira, TicketSyncStore cursors, Clock clock,
			AgenticProperties properties) {
		return new TicketUpdates(store, jira, cursors, TaskOrigin.JIRA, clock, Duration.ofDays(7),
				properties.jira().runLinkBase());
	}

	/** Posts pending status comments on a fixed interval, from one instance at a time (no duplicate comments). */
	@Bean
	PeriodicJob jiraUpdatesWatcher(TicketUpdates updates, AgenticProperties properties,
			ClusterConfiguration.JobLeases leases) {
		return new PeriodicJob("Jira status comments", properties.jira().updateInterval(), Duration.ofMinutes(10),
				updates::sweep, leases.forJob("jira-ticket-updates"));
	}
}
