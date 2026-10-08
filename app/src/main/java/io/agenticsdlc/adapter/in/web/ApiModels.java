package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.Task;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies of the v1 API. Kept separate from domain types so the API can evolve independently. */
final class ApiModels {

	private ApiModels() {
	}

	record SubmitTaskRequest(
			@NotBlank @Size(max = Task.MAX_TITLE_LENGTH) String title,
			@NotBlank @Size(max = Task.MAX_DESCRIPTION_LENGTH) String description,
			@NotNull @Valid RepositoryRequest repository,
			@Size(max = 255) String baseBranch) {
	}

	record RepositoryRequest(@NotNull ScmKind kind, @NotNull URI cloneUrl) {
	}

	record DecisionRequest(@NotNull Gate gate, @NotNull GateDecision decision, @Size(max = 4000) String comment) {
	}

	record CancelRequest(@Size(max = 4000) String reason) {
	}

	record RaiseRiskRequest(@NotNull RiskLevel risk, @NotBlank @Size(max = 4000) String reason) {
	}

	record RunResponse(UUID id, String state, String risk, List<String> gates, String pendingGate, String resumeState,
			int fixIterations, int reviewLoops, UsageResponse usage, long version, Instant createdAt,
			Instant updatedAt, TaskResponse task) {

		static RunResponse of(RunView view) {
			Run run = view.run();
			Task task = view.task();
			return new RunResponse(run.id(), run.state().name(), name(run.risk()),
					run.gatePolicy() == null ? List.of() : run.gatePolicy().gates().stream().map(Enum::name).toList(),
					name(run.pendingGate()), name(run.resumeState()), run.fixIterations(), run.reviewLoops(),
					new UsageResponse(run.usage().inputTokens(), run.usage().outputTokens(),
							run.usage().cacheReadTokens(), run.usage().cacheWriteTokens(),
							run.usage().costMicroUsd() / 1_000_000.0),
					run.version(), run.createdAt(), run.updatedAt(),
					new TaskResponse(task.id(), task.origin().name(), task.externalRef(), task.title(),
							task.description(), task.repository().kind().name(), task.repository().cloneUrl(),
							task.baseBranch(), task.trust().name(), task.requestedBy(), task.createdAt()));
		}
	}

	record UsageResponse(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
			double costUsd) {
	}

	record TaskResponse(UUID id, String origin, String externalRef, String title, String description,
			String scmKind, URI cloneUrl, String baseBranch, String trust, String requestedBy, Instant createdAt) {
	}

	record RunPage(List<RunResponse> items, Instant nextCreatedBefore) {
	}

	record RunEventResponse(long seq, String type, String actor, Map<String, Object> payload, Instant occurredAt) {

		static RunEventResponse of(RunEvent event) {
			return new RunEventResponse(event.seq(), event.type().name(), event.actor(), event.payload(),
					event.occurredAt());
		}
	}

	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}
}
