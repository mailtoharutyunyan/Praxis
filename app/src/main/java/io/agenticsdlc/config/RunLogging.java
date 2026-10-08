package io.agenticsdlc.config;

import io.agenticsdlc.core.engine.RunWorker;
import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;

/**
 * Tags every log line written while a run is processed with its id ({@code runId} in the MDC, a field in the JSON
 * logs): the worker puts the id into the Reactor context, and automatic context propagation restores it as MDC on
 * whatever thread continues the work.
 */
final class RunLogging {

	private static volatile boolean installed;

	private RunLogging() {
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		ContextRegistry.getInstance().registerThreadLocalAccessor(RunWorker.RUN_ID, () -> MDC.get(RunWorker.RUN_ID),
				value -> MDC.put(RunWorker.RUN_ID, value), () -> MDC.remove(RunWorker.RUN_ID));
		Hooks.enableAutomaticContextPropagation();
		installed = true;
	}
}
