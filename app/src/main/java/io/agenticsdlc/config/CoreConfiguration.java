package io.agenticsdlc.config;

import io.agenticsdlc.core.application.RepositoryPolicy;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.engine.RunLimits;
import io.agenticsdlc.core.insights.InsightsQueries;
import io.agenticsdlc.core.insights.InsightsStore;
import io.agenticsdlc.core.port.RunChangeSignals;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free core services to their adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgenticProperties.class)
class CoreConfiguration {

	@Bean
	NodeIdentity nodeIdentity(AgenticProperties properties) {
		return NodeIdentity.of(properties.worker().nodeId());
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	RunLimits runLimits(AgenticProperties properties) {
		AgenticProperties.Limits limits = properties.limits();
		return new RunLimits(limits.maxFixIterations(), limits.maxReviewLoops(), limits.maxTokens(),
				limits.maxCostMicroUsd(), limits.stageTimeout());
	}

	@Bean
	RepositoryPolicy repositoryPolicy(io.agenticsdlc.config.connectors.ConnectorSettings connectors) {
		return new RepositoryPolicy(connectors::allowedHosts);
	}

	@Bean
	TaskIntake taskIntake(RunStore store, Clock clock, RepositoryPolicy repositories) {
		return new TaskIntake(store, clock, UUID::randomUUID, repositories);
	}

	@Bean
	RunCommands runCommands(RunStore store, Clock clock, AgenticProperties properties) {
		return new RunCommands(store, clock, properties.gates().forbidSelfApproval(),
				properties.scm().feedback().maxRevisions());
	}

	@Bean
	RunQueries runQueries(RunStore store, RunChangeSignals signals, AgenticProperties properties) {
		return new RunQueries(store, signals, properties.events().fallbackPoll());
	}

	@Bean
	InsightsQueries insightsQueries(InsightsStore store, Clock clock) {
		return new InsightsQueries(store, clock);
	}
}
