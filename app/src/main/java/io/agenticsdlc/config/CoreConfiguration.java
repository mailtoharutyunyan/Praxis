package io.agenticsdlc.config;

import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.engine.RunLimits;
import io.agenticsdlc.core.port.RunChangeSignals;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free core services to their adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgenticProperties.class)
class CoreConfiguration {

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
	TaskIntake taskIntake(RunStore store, Clock clock) {
		return new TaskIntake(store, clock, UUID::randomUUID);
	}

	@Bean
	RunCommands runCommands(RunStore store, Clock clock, AgenticProperties properties) {
		return new RunCommands(store, clock, properties.gates().forbidSelfApproval());
	}

	@Bean
	RunQueries runQueries(RunStore store, RunChangeSignals signals, AgenticProperties properties) {
		return new RunQueries(store, signals, properties.events().fallbackPoll());
	}
}
