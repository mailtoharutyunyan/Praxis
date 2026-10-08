package io.agenticsdlc.adapter.out.stub;

import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Placeholder stages that walk a run through the whole pipeline without doing real work, so the API, worker,
 * gates and event stream can be exercised end to end before the agent stages exist (M2–M5). Enabled only with
 * {@code agentic.stub-stages.enabled=true} (local profile, tests). Triage reads the risk from a {@code [low]},
 * {@code [medium]} or {@code [high]} tag in the task title, defaulting to LOW.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("agentic.stub-stages.enabled")
public class StubStageHandlers {

	static final String ACTOR = "agent:stub";

	@Bean
	StageHandler stubTriage() {
		return new Stub(RunState.TRIAGING) {
			@Override
			StageOutcome outcome(StageContext context) {
				return new StageOutcome.Triaged(riskFromTitle(context.task().title()), "stub triage from title tag",
						Usage.ZERO);
			}
		};
	}

	@Bean
	StageHandler stubContext() {
		return new Stub(RunState.PREPARING_CONTEXT);
	}

	@Bean
	StageHandler stubSpec() {
		return new Stub(RunState.SPECIFYING);
	}

	@Bean
	StageHandler stubImplement() {
		return new Stub(RunState.IMPLEMENTING);
	}

	@Bean
	StageHandler stubVerify() {
		return new Stub(RunState.VERIFYING);
	}

	@Bean
	StageHandler stubReview() {
		return new Stub(RunState.REVIEWING);
	}

	@Bean
	StageHandler stubPublish() {
		return new Stub(RunState.PUBLISHING);
	}

	static RiskLevel riskFromTitle(String title) {
		String lower = title.toLowerCase(Locale.ROOT);
		if (lower.contains("[high]")) {
			return RiskLevel.HIGH;
		}
		return lower.contains("[medium]") ? RiskLevel.MEDIUM : RiskLevel.LOW;
	}

	private static class Stub implements StageHandler {

		private final RunState stage;

		Stub(RunState stage) {
			this.stage = stage;
		}

		@Override
		public RunState stage() {
			return stage;
		}

		@Override
		public Mono<StageOutcome> execute(StageContext context) {
			return context.emit(RunEventType.AGENT_MESSAGE, ACTOR, Map.of("message", "stub " + stage + " did nothing"))
					.then(Mono.fromSupplier(() -> outcome(context)));
		}

		StageOutcome outcome(StageContext context) {
			return StageOutcome.Completed.free();
		}

		@Override
		public boolean placeholder() {
			return true;
		}
	}
}
