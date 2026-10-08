package io.agenticsdlc.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.engine.RunWorker;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

class RunLoggingTest {

	@Test
	void theRunIdFromTheReactorContextIsInTheMdcOnAnyThread() {
		RunLogging.install();
		String seen = Mono.fromCallable(() -> MDC.get(RunWorker.RUN_ID))
				.subscribeOn(Schedulers.boundedElastic())
				.contextWrite(Context.of(RunWorker.RUN_ID, "run-42"))
				.block();
		assertThat(seen).isEqualTo("run-42");
		assertThat(MDC.get(RunWorker.RUN_ID)).isNull();
	}
}
