package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.workspace.BuildProfile;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SandboxSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Brings up a run's working copy and sandbox and runs commands in it, recording every command as a
 * {@code COMMAND_OUTPUT} event. Idempotent, so any stage can call {@link #prepare} after a restart.
 */
public final class RunWorkspace {

	public static final String ACTOR = "system:sandbox";

	private final RepositoryCheckout checkout;
	private final Sandbox sandbox;
	private final Duration commandTimeout;

	public RunWorkspace(RepositoryCheckout checkout, Sandbox sandbox, Duration commandTimeout) {
		this.checkout = Objects.requireNonNull(checkout, "checkout");
		this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
		this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
	}

	/** The checkout, its build profile and a running sandbox. */
	public record Prepared(CheckoutInfo checkout, BuildProfile profile) {
	}

	/** Errors with {@link UndetectableBuildException} when the repository's toolchain is unknown. */
	public Mono<Prepared> prepare(StageContext context) {
		return checkout.checkout(context.view())
				.flatMap(info -> BuildProfile.detect(info.rootEntries(), info.projectConfig())
						.map(profile -> sandbox.start(context.run().id(), new SandboxSpec(profile.image(), Map.of()))
								.thenReturn(new Prepared(info, profile)))
						.orElseGet(() -> Mono.error(new UndetectableBuildException(info))));
	}

	/** Run commands in order, stopping at the first failure. Emits the results that ran. */
	public Mono<List<CommandResult>> runAll(StageContext context, List<String> commands) {
		return Flux.fromIterable(commands)
				.concatMap(command -> run(context, command))
				.takeUntil(result -> !result.succeeded())
				.collectList();
	}

	public Mono<CommandResult> run(StageContext context, String command) {
		return sandbox.exec(context.run().id(), command, commandTimeout)
				.flatMap(result -> context.emit(RunEventType.COMMAND_OUTPUT, ACTOR, result.toPayload())
						.thenReturn(result));
	}

	/** A file of the working copy, read inside the sandbox. */
	public Mono<String> read(StageContext context, String path) {
		return sandbox.readFile(context.run().id(), path, 512 * 1024);
	}

	public Mono<String> diff(StageContext context) {
		return checkout.diff(context.run().id());
	}

	/** No supported build files and no complete {@code .agentic-sdlc.yml}. */
	public static final class UndetectableBuildException extends RuntimeException {
		public UndetectableBuildException(CheckoutInfo info) {
			super("cannot detect how to build this repository (root: " + info.rootEntries()
					+ "); add .agentic-sdlc.yml with image, build and test commands");
		}
	}
}
