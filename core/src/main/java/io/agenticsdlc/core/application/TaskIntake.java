package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.port.RunStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Turns an incoming request into a task and its first run. Idempotent per requester and key. */
public final class TaskIntake {

	private final RunStore store;
	private final Clock clock;
	private final Supplier<UUID> ids;
	private final RepositoryPolicy repositories;

	public TaskIntake(RunStore store, Clock clock, Supplier<UUID> ids, RepositoryPolicy repositories) {
		this.store = Objects.requireNonNull(store, "store");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.ids = Objects.requireNonNull(ids, "ids");
		this.repositories = Objects.requireNonNull(repositories, "repositories");
	}

	public Mono<RunStore.Submission> submit(NewTask request) {
		return Mono.fromCallable(() -> build(request))
				.flatMap(prepared -> store.submit(prepared.task(), prepared.run(), prepared.events()));
	}

	/**
	 * Text typed by an authenticated operator is trusted. Anything copied from another system (tickets, issues,
	 * chat) was written by someone we have not authenticated and may contain prompt injection.
	 */
	static Trust trustOf(TaskOrigin origin) {
		return origin == TaskOrigin.PROMPT ? Trust.TRUSTED : Trust.UNTRUSTED;
	}

	private Prepared build(NewTask request) {
		repositories.check(request.repository());
		Instant now = clock.instant();
		Task task = new Task(ids.get(), request.origin(), request.externalRef(), request.title(), request.description(),
				request.repository(), request.baseBranch(), trustOf(request.origin()), request.requestedBy(),
				request.idempotencyKey(), now);
		Run run = Run.start(ids.get(), task.id(), now);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskId", task.id().toString());
		payload.put("origin", task.origin().name());
		payload.put("externalRef", task.externalRef());
		payload.put("title", task.title());
		payload.put("repository", task.repository().cloneUrl().toString());
		payload.put("trust", task.trust().name());
		RunEvent created = RunEvent.of(run.id(), RunEventType.RUN_CREATED, task.requestedBy(), payload, now);
		return new Prepared(task, run, List.of(created));
	}

	private record Prepared(Task task, Run run, List<RunEvent> events) {
	}
}
