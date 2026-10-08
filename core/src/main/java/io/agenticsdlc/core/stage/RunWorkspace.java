package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.workspace.BuildPlan;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.Environments;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SandboxSpec;
import io.agenticsdlc.core.workspace.WorkspacePath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Brings up a run's working copy and sandbox and runs commands in it, recording every command as a
 * {@code COMMAND_OUTPUT} event. Idempotent, so any stage can call {@link #prepare} after a restart.
 * <p>
 * A working copy may hold several services (ADR-0006): each gets its own sandbox environment from its toolchain
 * image, sidecars start first, and verification builds and tests only the services a change touches.
 */
public final class RunWorkspace implements Environments {

	public static final String ACTOR = "system:sandbox";
	private static final int REMEMBERED_PLANS = 1_000;

	private final RepositoryCheckout checkout;
	private final Sandbox sandbox;
	private final Duration commandTimeout;
	/** Plans of runs prepared on this node, so agent tools can address services by name. */
	private final Map<UUID, BuildPlan> plans = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, BuildPlan> eldest) {
			return size() > REMEMBERED_PLANS;
		}
	});

	public RunWorkspace(RepositoryCheckout checkout, Sandbox sandbox, Duration commandTimeout) {
		this.checkout = Objects.requireNonNull(checkout, "checkout");
		this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
		this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
	}

	/** The checkout, its build plan and running sandbox environments. */
	public record Prepared(CheckoutInfo checkout, BuildPlan plan) {
	}

	/** Errors with {@link UndetectableBuildException} when no service's toolchain can be determined. */
	public Mono<Prepared> prepare(StageContext context) {
		UUID runId = context.run().id();
		return checkout.checkout(context.view())
				.flatMap(info -> plan(info)
						.map(plan -> {
							plans.put(runId, plan);
							return sandbox.startSidecars(runId, plan.sidecars())
									.thenMany(Flux.fromIterable(specs(plan)))
									.concatMap(spec -> sandbox.start(runId, spec))
									.then(Mono.just(new Prepared(info, plan)));
						})
						.orElseGet(() -> Mono.error(new UndetectableBuildException(info))));
	}

	/** The primary repository's services, then each companion repository's (ADR-0006). */
	static Optional<BuildPlan> plan(CheckoutInfo info) {
		Optional<BuildPlan> plan = BuildPlan.detect(info.files(), info.projectConfig());
		for (CheckoutInfo.Companion companion : info.companions()) {
			Optional<BuildPlan> theirs = BuildPlan.detect(companion.files(), companion.projectConfig())
					.map(p -> p.within(companion.path(), companion.alias()));
			if (theirs.isPresent()) {
				plan = Optional.of(plan.map(mine -> mine.plus(theirs.get())).orElse(theirs.get()));
			}
		}
		return plan;
	}

	/** One environment per distinct component; the main component's is {@link SandboxSpec#MAIN}. */
	static List<SandboxSpec> specs(BuildPlan plan) {
		List<SandboxSpec> specs = new ArrayList<>();
		for (BuildPlan.Component component : plan.components()) {
			specs.add(new SandboxSpec(environment(plan, component), component.profile().image(), plan.env()));
		}
		return specs;
	}

	static String environment(BuildPlan plan, BuildPlan.Component component) {
		if (component.equals(plan.main())) {
			return SandboxSpec.MAIN;
		}
		return component.name().equals(SandboxSpec.MAIN) ? "main-service" : component.name();
	}

	/** Setup and build of every service, continuing past failures; the baseline may already be broken. */
	public Mono<List<CommandResult>> baseline(StageContext context, Prepared prepared) {
		return Flux.fromIterable(prepared.plan().components())
				.concatMap(component -> {
					List<String> commands = new ArrayList<>();
					if (component.profile().setup() != null) {
						commands.add(component.profile().setup());
					}
					commands.add(component.profile().build());
					return runAll(context, prepared.plan(), component, commands);
				})
				.map(List::getLast)
				.collectList();
	}

	/**
	 * Build and test the services {@code changedFiles} touch (all of them when none or shared files changed),
	 * stopping at the first failure. Emits the results that ran.
	 */
	public Mono<List<CommandResult>> verify(StageContext context, Prepared prepared, Collection<String> changedFiles) {
		BuildPlan plan = prepared.plan();
		List<BuildPlan.Component> affected = changedFiles.isEmpty() ? plan.components() : plan.affected(changedFiles);
		if (affected.isEmpty()) {
			affected = plan.components();
		}
		List<CommandResult> results = new ArrayList<>();
		return Flux.fromIterable(affected)
				.concatMap(component -> runAll(context, plan, component, component.profile().verifyCommands()))
				.doOnNext(results::addAll)
				.takeUntil(ran -> !ran.getLast().succeeded())
				.then(Mono.fromSupplier(() -> List.copyOf(results)));
	}

	/** Run commands in the main environment in order, stopping at the first failure. Emits the results that ran. */
	public Mono<List<CommandResult>> runAll(StageContext context, List<String> commands) {
		return Flux.fromIterable(commands)
				.concatMap(command -> run(context, command))
				.takeUntil(result -> !result.succeeded())
				.collectList();
	}

	private Mono<List<CommandResult>> runAll(StageContext context, BuildPlan plan, BuildPlan.Component component,
			List<String> commands) {
		return Flux.fromIterable(commands)
				.concatMap(command -> run(context, environment(plan, component), component, command))
				.takeUntil(result -> !result.succeeded())
				.collectList();
	}

	public Mono<CommandResult> run(StageContext context, String command) {
		return sandbox.exec(context.run().id(), command, commandTimeout)
				.flatMap(result -> context.emit(RunEventType.COMMAND_OUTPUT, ACTOR, result.toPayload())
						.thenReturn(result));
	}

	private Mono<CommandResult> run(StageContext context, String environment, BuildPlan.Component component,
			String command) {
		return sandbox.exec(context.run().id(), environment, component.inDirectory(command), commandTimeout)
				.flatMap(result -> {
					Map<String, Object> payload = new LinkedHashMap<>(result.toPayload());
					payload.put("service", component.name());
					return context.emit(RunEventType.COMMAND_OUTPUT, ACTOR, payload).thenReturn(result);
				});
	}

	@Override
	public Optional<Target> target(UUID runId, String service) {
		BuildPlan plan = plans.get(runId);
		if (plan == null) {
			return Optional.empty();
		}
		return plan.components().stream().filter(c -> c.name().equals(service)).findFirst()
				.map(c -> new Target(environment(plan, c), c));
	}

	@Override
	public List<String> services(UUID runId) {
		BuildPlan plan = plans.get(runId);
		return plan == null ? List.of() : plan.components().stream().map(BuildPlan.Component::name).toList();
	}

	/** A file of the working copy, read inside the sandbox. */
	public Mono<String> read(StageContext context, String path) {
		return sandbox.readFile(context.run().id(), path, 512 * 1024);
	}

	public Mono<String> diff(StageContext context) {
		return checkout.diff(context.run().id());
	}

	/**
	 * Puts files of the working copy back as they are in the base commit: changed ones are rewritten, added ones
	 * deleted. The writes run in the sandbox as its user, like the agent's own, so links the agent made cannot
	 * redirect them on the host. Text files only; anything else fails.
	 */
	public Mono<Void> restore(StageContext context, Collection<String> paths) {
		UUID runId = context.run().id();
		return Flux.fromIterable(paths)
				.concatMap(path -> checkout.baseFile(runId, path).flatMap(base -> base.isPresent()
						? sandbox.writeFile(runId, path, base.get())
						: sandbox.exec(runId, "rm -f -- " + WorkspacePath.shellQuote(WorkspacePath.relative(path)),
								commandTimeout).flatMap(result -> result.succeeded() ? Mono.<Void>empty()
										: Mono.error(new IllegalStateException("could not delete " + path + ": "
												+ result.tail(500))))))
				.then();
	}

	/** No supported build files and no complete {@code .agentic-sdlc.yml}. */
	public static final class UndetectableBuildException extends RuntimeException {
		public UndetectableBuildException(CheckoutInfo info) {
			super("cannot detect how to build this repository (root: " + info.rootEntries()
					+ "); add .agentic-sdlc.yml with image, build and test commands, or a services: list for a monorepo");
		}
	}
}
