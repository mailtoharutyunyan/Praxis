package io.agenticsdlc.config;

import io.agenticsdlc.adapter.in.eval.EvalCommand;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.eval.EvalHarness;
import io.agenticsdlc.core.eval.EvalReport;
import io.agenticsdlc.core.workspace.Sandbox;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Evaluation harness (M7). Run a suite with:
 * {@code java -jar app.jar --agentic.eval.suite=evals/suite.yaml --agentic.eval.output=target/eval}
 * The process exits when done: code 0, or 1 when pass@1 is below {@code agentic.eval.min-pass-rate}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("agentic.sandbox.enabled")
class EvalConfiguration {

	@Bean
	EvalHarness evalHarness(TaskIntake intake, RunQueries queries, RunCommands commands, Sandbox sandbox, Clock clock,
			AgenticProperties properties) {
		return new EvalHarness(intake, queries, commands, sandbox, clock, Duration.ofSeconds(2),
				properties.limits().stageTimeout().multipliedBy(6), properties.sandbox().commandTimeout());
	}

	@Bean
	EvalCommand evalCommand(EvalHarness harness, JsonMapper json) {
		return new EvalCommand(harness, json);
	}

	@Bean
	@ConditionalOnProperty("agentic.eval.suite")
	ApplicationRunner evalRunner(EvalCommand command, ConfigurableApplicationContext context,
			org.springframework.core.env.Environment env) {
		return args -> {
			Path suite = Path.of(env.getRequiredProperty("agentic.eval.suite"));
			Path output = Path.of(env.getProperty("agentic.eval.output", "eval-report"));
			double minPassRate = env.getProperty("agentic.eval.min-pass-rate", Double.class, 0.0);
			EvalReport report = command.execute(suite, output);
			int code = report.passAt1() + 1e-9 >= minPassRate ? 0 : 1;
			System.exit(SpringApplication.exit(context, () -> code));
		};
	}
}
