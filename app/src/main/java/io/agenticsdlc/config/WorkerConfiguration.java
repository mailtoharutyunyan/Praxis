package io.agenticsdlc.config;

import io.agenticsdlc.core.engine.RunLimits;
import io.agenticsdlc.core.engine.RunWorker;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.port.RunStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "agentic.worker.enabled", matchIfMissing = true)
class WorkerConfiguration {

	@Bean
	RunWorker runWorker(RunStore store, ObjectProvider<StageHandler> handlers, RunLimits limits, Clock clock,
			AgenticProperties properties, MeterRegistry meters) {
		return new RunWorker(store, handlers.orderedStream().toList(), limits, clock, workerId(), properties.worker().lease(),
				new MeteredWorkerListener(meters));
	}

	@Bean
	WorkerLifecycle workerLifecycle(RunWorker worker, AgenticProperties properties) {
		return new WorkerLifecycle(worker, properties.worker());
	}

	/** Unique per process, readable in the database's lease_owner column. */
	private static String workerId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException e) {
			host = "unknown-host";
		}
		return host + "/" + ProcessHandle.current().pid() + "/" + UUID.randomUUID().toString().substring(0, 8);
	}
}
