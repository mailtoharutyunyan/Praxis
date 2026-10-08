package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.core.memory.RepoFact;
import io.agenticsdlc.core.memory.RepoMemory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Repository memory: what agents learned, for people to review, activate or disable. */
@RestController
@RequestMapping("/api/v1/memory")
class MemoryController {

	private final RepoMemory memory;
	private final Clock clock;
	private final AgenticProperties.Memory settings;

	MemoryController(RepoMemory memory, Clock clock, AgenticProperties properties) {
		this.memory = memory;
		this.clock = clock;
		this.settings = properties.memory();
	}

	record CitationResponse(String path, int line, String snippet) {
	}

	record FactResponse(UUID id, String repository, String fact, List<CitationResponse> citations, String status,
			UUID sourceRunId, Instant createdAt, Instant expiresAt) {
		static FactResponse of(RepoFact fact) {
			return new FactResponse(fact.id(), fact.repository(), fact.fact(), fact.citations().stream()
					.map(c -> new CitationResponse(c.path(), c.line(), c.snippet())).toList(), fact.status().name(),
					fact.sourceRunId(), fact.createdAt(), fact.expiresAt());
		}
	}

	record StatusRequest(@NotNull RepoFact.Status status) {
	}

	/** @param repository a clone URL or repository key; all repositories when absent */
	@GetMapping
	Mono<List<FactResponse>> list(@RequestParam(required = false) String repository,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		String key = repository == null || repository.isBlank() ? null
				: repository.contains("://") ? RepoFact.key(URI.create(repository.strip())) : repository.strip().toLowerCase();
		return memory.list(key, limit).map(FactResponse::of).collectList();
	}

	/** An approver activates a fact (for another retention period) or disables it. */
	@PostMapping("/{id}/status")
	Mono<FactResponse> setStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
		if (request.status() == RepoFact.Status.CANDIDATE) {
			throw new IllegalArgumentException("status must be ACTIVE or DISABLED");
		}
		Instant expires = request.status() == RepoFact.Status.ACTIVE ? clock.instant().plus(settings.retention())
				: clock.instant();
		return memory.setStatus(id, request.status(), expires).map(FactResponse::of)
				.switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no fact " + id)));
	}
}
