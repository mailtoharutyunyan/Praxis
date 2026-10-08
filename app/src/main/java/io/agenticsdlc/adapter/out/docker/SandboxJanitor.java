package io.agenticsdlc.adapter.out.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Periodically removes sandboxes and working copies of runs that are finished (DONE, FAILED, CANCELLED) or no longer
 * exist. Runs at PR_OPEN keep theirs for follow-up changes. Safe to run on every instance: removal is idempotent.
 */
public class SandboxJanitor implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(SandboxJanitor.class);

	private final DockerClient docker;
	private final RunStore store;
	private final Sandbox sandbox;
	private final RepositoryCheckout checkout;
	private final Duration interval;
	private volatile Disposable schedule;

	public SandboxJanitor(DockerClient docker, RunStore store, Sandbox sandbox, RepositoryCheckout checkout,
			Duration interval) {
		this.docker = docker;
		this.store = store;
		this.sandbox = sandbox;
		this.checkout = checkout;
		this.interval = interval;
	}

	/** One sweep; emits the run ids that were cleaned up. */
	public Flux<UUID> sweep() {
		return Mono.fromCallable(() -> docker.listContainersCmd().withShowAll(true)
						.withLabelFilter(List.of(DockerSandbox.LABEL_RUN)).exec())
				.subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(Flux::fromIterable)
				.map(Container::getLabels)
				.map(labels -> UUID.fromString(labels.get(DockerSandbox.LABEL_RUN)))
				.concatMap(runId -> store.find(runId)
						.map(view -> view.run().state())
						.defaultIfEmpty(RunState.DONE)
						.filter(RunState::isTerminal)
						.flatMap(state -> sandbox.destroy(runId).then(checkout.remove(runId)).thenReturn(runId)))
				.doOnNext(runId -> log.info("cleaned up sandbox and workspace of finished run {}", runId));
	}

	@Override
	public void start() {
		schedule = Flux.interval(interval, interval)
				.concatMap(tick -> sweep().onErrorResume(e -> {
					log.warn("sandbox cleanup failed", e);
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
