package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.adapter.in.web.ApiModels.CancelRequest;
import io.agenticsdlc.adapter.in.web.ApiModels.DecisionRequest;
import io.agenticsdlc.adapter.in.web.ApiModels.RaiseRiskRequest;
import io.agenticsdlc.adapter.in.web.ApiModels.RunEventResponse;
import io.agenticsdlc.adapter.in.web.ApiModels.RunPage;
import io.agenticsdlc.adapter.in.web.ApiModels.RunResponse;
import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/runs")
class RunController {

	private final RunQueries queries;
	private final RunCommands commands;
	private final Duration keepAlive;
	private final EventStreamShutdown shutdown;

	RunController(RunQueries queries, RunCommands commands, AgenticProperties properties,
			EventStreamShutdown shutdown) {
		this.queries = queries;
		this.commands = commands;
		this.keepAlive = properties.events().keepAlive();
		this.shutdown = shutdown;
	}

	@GetMapping
	Mono<RunPage> list(@RequestParam(name = "state", required = false) List<RunState> states,
			@RequestParam(required = false) Instant createdBefore, @RequestParam(required = false) UUID beforeId,
			@RequestParam(defaultValue = "50") @Min(1) @Max(RunQueries.MAX_PAGE) int limit) {
		EnumSet<RunState> filter = states == null || states.isEmpty() ? EnumSet.noneOf(RunState.class)
				: EnumSet.copyOf(states);
		RunStore.Cursor before = createdBefore == null ? null : new RunStore.Cursor(createdBefore, beforeId);
		return queries.list(filter, before, limit)
				.map(RunResponse::of)
				.collectList()
				.map(items -> items.size() < limit ? new RunPage(items, null, null)
						: new RunPage(items, items.getLast().createdAt(), items.getLast().id()));
	}

	@GetMapping("/{runId}")
	Mono<RunResponse> get(@PathVariable UUID runId) {
		return queries.get(runId).map(RunResponse::of);
	}

	/** Event log page (JSON). For live updates use the {@code text/event-stream} variant of this endpoint. */
	@GetMapping(path = "/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
	Flux<RunEventResponse> events(@PathVariable UUID runId,
			@RequestParam(defaultValue = "0") @Min(0) long afterSeq,
			@RequestParam(defaultValue = "200") @Min(1) @Max(RunQueries.MAX_PAGE) int limit) {
		return queries.events(runId, afterSeq, limit).map(RunEventResponse::of);
	}

	/**
	 * Live event stream (SSE). Each event's {@code id} is its sequence number, so a reconnecting client sends
	 * {@code Last-Event-ID} and resumes without gaps. The stream ends after the run reaches a terminal state.
	 */
	@GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	Flux<ServerSentEvent<RunEventResponse>> stream(@PathVariable UUID runId,
			@RequestParam(defaultValue = "0") @Min(0) long afterSeq,
			@RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
		long from = lastEventId != null ? lastEventId : afterSeq;
		Flux<ServerSentEvent<RunEventResponse>> events = queries.follow(runId, from)
				.takeUntilOther(shutdown.signal())
				.map(event -> ServerSentEvent.builder(RunEventResponse.of(event))
						.id(Long.toString(event.seq()))
						.event(event.type().name())
						.build());
		return events.publish(shared -> Flux.merge(shared,
				Flux.interval(keepAlive)
						.map(tick -> ServerSentEvent.<RunEventResponse>builder().comment("keep-alive").build())
						.takeUntilOther(shared.ignoreElements())));
	}

	@PostMapping("/{runId}/decisions")
	Mono<RunResponse> decide(@PathVariable UUID runId, @Valid @RequestBody DecisionRequest request,
			@AuthenticationPrincipal Jwt user) {
		return respond(commands.decide(runId, request.gate(), request.decision(), request.comment(), user.getSubject(),
				aliases(user)));
	}

	/** Names that identify the user in outside systems, so they cannot approve tickets they filed there. */
	static Set<String> aliases(Jwt user) {
		Set<String> aliases = new java.util.HashSet<>();
		for (String claim : List.of("email", "preferred_username", "upn")) {
			String value = user.getClaimAsString(claim);
			if (value != null && !value.isBlank()) {
				aliases.add(value);
			}
		}
		return aliases;
	}

	@PostMapping("/{runId}/cancel")
	Mono<RunResponse> cancel(@PathVariable UUID runId, @Valid @RequestBody(required = false) CancelRequest request,
			@AuthenticationPrincipal Jwt user) {
		return respond(commands.cancel(runId, request == null ? null : request.reason(), user.getSubject()));
	}

	@PostMapping("/{runId}/resume")
	Mono<RunResponse> resume(@PathVariable UUID runId, @AuthenticationPrincipal Jwt user) {
		return respond(commands.resume(runId, user.getSubject()));
	}

	@PostMapping("/{runId}/risk")
	Mono<RunResponse> raiseRisk(@PathVariable UUID runId, @Valid @RequestBody RaiseRiskRequest request,
			@AuthenticationPrincipal Jwt user) {
		return respond(commands.raiseRisk(runId, request.risk(), request.reason(), user.getSubject()));
	}

	private Mono<RunResponse> respond(Mono<Run> command) {
		return command.flatMap(run -> queries.get(run.id())).map(RunResponse::of);
	}
}
