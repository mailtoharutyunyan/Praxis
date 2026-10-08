package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.core.application.RunNotFoundException;
import io.agenticsdlc.core.application.SelfApprovalException;
import io.agenticsdlc.core.port.ConcurrentRunUpdateException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem details. Domain rule violations (illegal transition, wrong gate) are 409:
 * the request was understood but conflicts with the run's current state.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
	private static final String TYPE_BASE = "https://agentic-sdlc.dev/problems/";

	@ExceptionHandler(RunNotFoundException.class)
	ProblemDetail notFound(RunNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "run-not-found", "Run not found", e.getMessage());
	}

	@ExceptionHandler(SelfApprovalException.class)
	ProblemDetail selfApproval(SelfApprovalException e) {
		return problem(HttpStatus.FORBIDDEN, "self-approval", "Self-approval is not allowed", e.getMessage());
	}

	@ExceptionHandler(ConcurrentRunUpdateException.class)
	ProblemDetail concurrent(ConcurrentRunUpdateException e) {
		return problem(HttpStatus.CONFLICT, "concurrent-update", "Run changed concurrently", e.getMessage());
	}

	@ExceptionHandler(IllegalStateException.class)
	ProblemDetail ruleViolation(IllegalStateException e) {
		return problem(HttpStatus.CONFLICT, "invalid-state", "Not allowed in the run's current state", e.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalid(IllegalArgumentException e) {
		return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception e) {
		log.error("unhandled API error", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal error",
				"Unexpected error; see server logs.");
	}

	private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create(TYPE_BASE + type));
		problem.setTitle(title);
		return problem;
	}
}
