package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.adapter.in.web.ApiModels.RunResponse;
import io.agenticsdlc.adapter.in.web.ApiModels.SubmitTaskRequest;
import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.TaskOrigin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Prompt intake: an authenticated operator describes a task in free text. */
@RestController
@RequestMapping("/api/v1/tasks")
class TaskController {

	private final TaskIntake intake;

	TaskController(TaskIntake intake) {
		this.intake = intake;
	}

	/**
	 * Creates a task and its first run. With an {@code Idempotency-Key}, a retried request returns the original run
	 * with 200 instead of 201.
	 */
	@PostMapping
	Mono<ResponseEntity<RunResponse>> submit(@Valid @RequestBody SubmitTaskRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 255) String idempotencyKey,
			@AuthenticationPrincipal Jwt user) {
		NewTask task = new NewTask(TaskOrigin.PROMPT, null, request.title(), request.description(),
				new RepositoryRef(request.repository().kind(), request.repository().cloneUrl()), request.baseBranch(),
				user.getSubject(), idempotencyKey, request.companionList(),
				request.reviewPlanRequested());
		return intake.submit(task).map(submission -> ResponseEntity
				.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.location(URI.create("/api/v1/runs/" + submission.view().run().id()))
				.body(RunResponse.of(submission.view())));
	}
}
