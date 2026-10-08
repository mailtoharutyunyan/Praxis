package io.agenticsdlc.core.engine;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/** What a stage handler may see and do while it runs: read the run and task, and publish progress events. */
public final class StageContext {

	private final RunView view;
	private final RunStore store;
	private final String leaseOwner;
	private final Clock clock;
	private final AtomicReference<Usage> spent = new AtomicReference<>(Usage.ZERO);
	static final int HISTORY_LIMIT = 500;
	private static final Set<RunEventType> HISTORY_TYPES = Set.of(RunEventType.ARTIFACT_PRODUCED,
			RunEventType.GATE_DECIDED, RunEventType.STAGE_COMPLETED);

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

	/**
	 * The run's milestones so far, oldest first: artifacts, gate decisions and stage results (feedback, specs and
	 * failures from earlier stages). Tool calls and command output are left out, and only the newest
	 * {@value #HISTORY_LIMIT} milestones are read, so long runs stay cheap.
	 */
	public Mono<List<RunEvent>> history() {
		return store.latestEvents(run().id(), HISTORY_TYPES, HISTORY_LIMIT).collectList();
	}

	/** Record model usage as it happens, so a stage that fails or times out still accounts for it. */
	public void recordSpend(Usage usage) {
		spent.accumulateAndGet(usage, Usage::plus);
	}

	/** Usage recorded so far by this stage. */
	public Usage spent() {
		return spent.get();
	}

	/** Publish a progress event (tool call, command output, agent message) to the run's log and live stream. */
	public Mono<Void> emit(RunEventType type, String actor, Map<String, Object> payload) {
		return store.append(run().id(), leaseOwner, List.of(RunEvent.of(run().id(), type, actor, payload, clock.instant())));
	}
}
