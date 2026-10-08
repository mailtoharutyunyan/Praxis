package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.core.domain.Companion;
import io.agenticsdlc.core.domain.RepositoryRef;
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
			@Size(max = 255) String baseBranch,
			@Valid @Size(max = Task.MAX_COMPANIONS) List<CompanionRequest> companions) {

		/** Companion repositories as domain values; aliases default to the repository name. */
		List<Companion> companionList() {
			return companions == null ? List.of() : companions.stream().map(c -> {
				RepositoryRef ref = new RepositoryRef(c.kind() == null ? repository.kind() : c.kind(), c.cloneUrl());
				return new Companion(c.alias() == null || c.alias().isBlank() ? Companion.aliasFor(ref) : c.alias(), ref,
						c.baseBranch());
			}).toList();
		}
	}

	/** Another repository changed in the same run (ADR-0006); {@code kind} defaults to the primary's. */
	record CompanionRequest(@Size(max = 40) String alias, ScmKind kind, @NotNull URI cloneUrl,
			@Size(max = 255) String baseBranch) {
	}

	record CompanionResponse(String alias, String scmKind, URI cloneUrl, String baseBranch) {
	}

	record RepositoryRequest(@NotNull ScmKind kind, @NotNull URI cloneUrl) {
	}

	record DecisionRequest(@NotNull Gate gate, @NotNull GateDecision decision, @Size(max = 4000) String comment) {
	}

	record CancelRequest(@Size(max = 4000) String reason) {
	}

	/** Changes wanted on the run's open pull request; {@code location} is optional, e.g. {@code src/App.java:42}. */
	record RevisionBody(@NotBlank @Size(max = 20_000) String text, @Size(max = 500) String location) {
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
							task.baseBranch(), task.trust().name(), task.requestedBy(), task.createdAt(),
							task.companions().stream().map(c -> new CompanionResponse(c.alias(), c.repository().kind().name(),
									c.repository().cloneUrl(), c.baseBranch())).toList()));
		}
	}

	record UsageResponse(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
			double costUsd) {
	}

	record TaskResponse(UUID id, String origin, String externalRef, String title, String description,
			String scmKind, URI cloneUrl, String baseBranch, String trust, String requestedBy, Instant createdAt,
			List<CompanionResponse> companions) {
	}

	/** Pass both {@code next*} values back as {@code createdBefore} and {@code beforeId} for the next page. */
	record RunPage(List<RunResponse> items, Instant nextCreatedBefore, UUID nextBeforeId) {
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
