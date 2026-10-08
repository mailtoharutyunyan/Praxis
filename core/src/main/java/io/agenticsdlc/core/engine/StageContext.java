package io.agenticsdlc.core.engine;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** What a stage handler may see and do while it runs: read the run and task, and publish progress events. */
public final class StageContext {

	private final RunView view;
	private final RunStore store;
	private final String leaseOwner;
	private final Clock clock;

	public StageContext(RunView view, RunStore store, String leaseOwner, Clock clock) {
		this.view = Objects.requireNonNull(view, "view");
		this.store = Objects.requireNonNull(store, "store");
		this.leaseOwner = Objects.requireNonNull(leaseOwner, "leaseOwner");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public RunView view() {
		return view;
	}

	public Run run() {
		return view.run();
	}

	public Task task() {
		return view.task();
	}

	public Clock clock() {
		return clock;
	}

	/** The run's event log so far, oldest first (feedback, artifacts and failures from earlier stages). */
	public Mono<List<RunEvent>> history() {
		return store.events(run().id(), 0, Integer.MAX_VALUE).collectList();
	}

	/** Publish a progress event (tool call, command output, agent message) to the run's log and live stream. */
	public Mono<Void> emit(RunEventType type, String actor, Map<String, Object> payload) {
		return store.append(run().id(), leaseOwner, List.of(RunEvent.of(run().id(), type, actor, payload, clock.instant())));
	}
}
