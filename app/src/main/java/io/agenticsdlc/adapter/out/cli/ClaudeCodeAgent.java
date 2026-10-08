package io.agenticsdlc.adapter.out.cli;

import static io.agenticsdlc.core.workspace.WorkspacePath.shellQuote;

import io.agenticsdlc.config.CliAgentProperties;
import io.agenticsdlc.core.agent.AgentLoop;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.AgentTool;
import io.agenticsdlc.core.agent.ExternalAgent;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.workspace.CommandFailedException;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SandboxSpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agents on Claude Code in headless mode ({@code claude -p --output-format stream-json}) inside the run's sandbox
 * (ADR-0008). Each call writes its instructions and brief to a scratch directory in the container, runs the CLI with
 * the tools its {@link Access} allows and the credential in that command's environment only, records the streamed
 * transcript as run events, stops the CLI if it overspends the token budget, and maps its result to an
 * {@link AgentLoop.Outcome}.
 * <p>
 * Tasks from untrusted sources (Jira, Slack, issues) stay on the API engine unless
 * {@code agentic.agent.cli.untrusted-tasks} is set: the CLI holds a credential in the same container as the code it
 * runs. The sandbox needs network access to {@code api.anthropic.com}, through the egress proxy.
 */
public final class ClaudeCodeAgent implements ExternalAgent {

	/**
	 * How agents run on Claude Code, as configured now.
	 *
	 * @param token Claude subscription token from {@code claude setup-token}, or an Anthropic API key
	 * @param model {@code --model} for roles without their own; empty for the CLI's default
	 * @param roleModels {@code --model} by role: {@code planner}, {@code reviewer}, {@code triage}
	 * @param apiFallback whether API models are configured too, so untrusted tasks can run on the API engine
	 */
	public record Setup(String token, String model, Map<String, String> roleModels, boolean apiFallback) {

		public Setup {
			Objects.requireNonNull(token, "token");
			Objects.requireNonNull(model, "model");
			roleModels = Map.copyOf(roleModels);
		}

		String model(AgentRole role) {
			return roleModels.getOrDefault(role.name().toLowerCase(Locale.ROOT), model);
		}

		@Override
		public String toString() {
			return "Setup[token=" + (token.isBlank() ? "" : "***") + ", model=" + model + ", roleModels=" + roleModels
					+ ", apiFallback=" + apiFallback + "]";
		}
	}

	/** Shown to the CLI after the role's instructions, which name the API engine's tools. */
	static final String ENGINE_NOTE = """


			You are running as Claude Code in a sandbox, as one step of an automated pipeline. Where these \
			instructions name tools, use your own: list_files, view_file and search are Glob, Read and Grep; \
			edit_file and create_file are Edit and Write; run_command is Bash. The working copy has no git metadata, \
			so show_diff is the file %s: the changes against the base commit when you started (your own edits since \
			are not in it). You cannot ask anyone questions; your final reply is read by the pipeline.""";

	/** Replaces the remember tool, which the CLI does not have; the facts are stored through it afterwards. */
	static final String MEMORY_NOTE = """

			To remember a durable fact about this repository for later changes (a build or test quirk, a convention, \
			where something lives), write it to %s with the Write tool: one JSON object per line, \
			{"fact": "one sentence, at most 300 characters", "citations": [{"path": "path/in/repo", "line": 12}]}, \
			citing the lines that show it. Save at most three, only ones a later change would need.""";
	private static final int MAX_FACTS_PER_CALL = 3;
	private static final int CHUNK_BYTES = 48 * 1024;
	private static final Duration FILE_TIMEOUT = Duration.ofMinutes(1);

	private final Sandbox sandbox;
	private final RepositoryCheckout checkout;
	private final CliAgentProperties properties;
	private final Supplier<Optional<Setup>> setup;
	private final boolean networked;
	private final long maxCostMicroUsd;
	private final JsonMapper json;

	/**
	 * @param setup read per task and call, so a change in the UI applies to the next stage; empty when agents run on
	 *        the API engine
	 * @param sandboxNetwork {@code agentic.sandbox.network}; with {@code none} the CLI cannot reach its API
	 * @param maxCostMicroUsd the run's cost limit, passed on as {@code --max-budget-usd} less what is spent
	 */
	public ClaudeCodeAgent(Sandbox sandbox, RepositoryCheckout checkout, CliAgentProperties properties,
			Supplier<Optional<Setup>> setup, String sandboxNetwork, long maxCostMicroUsd, JsonMapper json) {
		this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
		this.checkout = Objects.requireNonNull(checkout, "checkout");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.setup = Objects.requireNonNull(setup, "setup");
		this.networked = !"none".equals(sandboxNetwork);
		this.maxCostMicroUsd = maxCostMicroUsd;
		this.json = Objects.requireNonNull(json, "json");
	}

	@Override
	public Choice choose(Task task) {
		Optional<Setup> current = setup.get();
		if (current.isEmpty()) {
			return Choice.LOOP;
		}
		if (task.trust() == Trust.UNTRUSTED && !properties.untrustedTasks()) {
			return current.get().apiFallback() ? Choice.LOOP : new Choice.Unavailable("this task comes from "
					+ task.origin() + ", an untrusted source, and untrusted tasks never run on Claude Code "
					+ "(agentic.agent.cli.untrusted-tasks); they use the API engine, which has no model configured. Add "
					+ "an API key to the AI model connector, or submit the task yourself");
		}
		if (!networked) {
			return new Choice.Unavailable("agents run on Claude Code, which must reach api.anthropic.com from the "
					+ "sandbox, but the sandbox has no network (agentic.sandbox.network=none). Use the sandbox network "
					+ "with the egress proxy (see the README)");
		}
		if (current.get().token().isBlank()) {
			return new Choice.Unavailable("agents run on Claude Code, but no token is configured: run `claude "
					+ "setup-token` and paste the token in Settings, AI model");
		}
		return Choice.EXTERNAL;
	}

	@Override
	public Mono<AgentLoop.Outcome> run(StageContext context, AgentRole role, Access access, String actor, String system,
			String brief, long tokenBudget) {
		return run(context, role, access, actor, system, brief, tokenBudget, SandboxSpec.MAIN, null);
	}

	@Override
	public Mono<AgentLoop.Outcome> run(StageContext context, AgentRole role, Access access, String actor, String system,
			String brief, long tokenBudget, String environment, AgentTool remember) {
		Setup current = setup.get().orElseThrow(() -> new IllegalStateException("agents do not run on Claude Code"));
		UUID runId = context.run().id();
		String dir = ClaudeCodeCommand.SCRATCH + "/" + UUID.randomUUID();
		boolean facts = remember != null && access != Access.NONE;
		ClaudeCodeCommand command = ClaudeCodeCommand.of(properties.binary(), current.token(), current.model(role),
				properties.maxTurns(), access, dir, remainingUsd(context), facts);
		StreamJsonTranscript transcript = new StreamJsonTranscript(context, actor, current.token(), tokenBudget, json);
		String instructions = access == Access.NONE ? system : system + ENGINE_NOTE.formatted(dir + "/changes.diff")
				+ (facts ? MEMORY_NOTE.formatted(dir + "/" + ClaudeCodeCommand.MEMORY_FILE) : "");
		Mono<Void> files = write(runId, environment, dir + "/system.md", instructions)
				.then(write(runId, environment, dir + "/brief.md", brief))
				.then(access == Access.NONE ? Mono.empty()
						: checkout.diff(runId).flatMap(diff -> write(runId, environment, dir + "/changes.diff", diff)));
		return files
				.thenMany(sandbox.execLines(runId, environment, command.line(), command.env(), properties.timeout()))
				.concatMap(line -> transcript.accept(line).thenReturn(transcript.overBudget()))
				// Cancelling the output kills the CLI.
				.takeUntil(over -> over)
				.then(Mono.fromSupplier(transcript::outcome))
				.onErrorResume(CommandFailedException.class, e -> Mono.just(transcript.failed(e.result())))
				.flatMap(outcome -> access == Access.FULL || access == Access.TESTS_ONLY
						? withoutCredential(runId, current.token(), outcome) : Mono.just(outcome))
				.flatMap(outcome -> facts ? storeFacts(context, actor, environment, dir, remember).thenReturn(outcome)
						: Mono.just(outcome))
				.flatMap(outcome -> sandbox.exec(runId, environment, "rm -rf " + shellQuote(dir), FILE_TIMEOUT)
						.onErrorResume(e -> Mono.empty()).thenReturn(outcome));
	}

	/**
	 * Stores the facts the CLI wrote, each through the remember tool, which checks its citations against the working
	 * copy and the run's limit, and records the call and its result as the agent loop does. A malformed or rejected
	 * fact is recorded and skipped; it never fails the stage.
	 */
	private Mono<Void> storeFacts(StageContext context, String actor, String environment, String dir, AgentTool remember) {
		UUID runId = context.run().id();
		String file = dir + "/" + ClaudeCodeCommand.MEMORY_FILE;
		return sandbox.exec(runId, environment, "cat " + shellQuote(file) + " 2>/dev/null || true", FILE_TIMEOUT)
				.flatMapMany(result -> Flux.fromStream(result.output().lines().map(String::strip)
						.filter(line -> line.startsWith("{")).limit(MAX_FACTS_PER_CALL)))
				.index()
				.concatMap(entry -> {
					String raw = entry.getT2();
					Map<String, Object> arguments;
					try {
						arguments = json.readValue(raw, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
						});
					}
					catch (RuntimeException e) {
						return Mono.empty();
					}
					ToolCall call = new ToolCall("memory-" + entry.getT1(), remember.spec().name(), arguments, raw);
					return context.emit(RunEventType.TOOL_CALLED, actor, Map.of("tool", call.name(), "callId", call.id(),
							"arguments", raw))
							.then(remember.execute(runId, call)
									.map(output -> Map.<String, Object>of("tool", call.name(), "callId", call.id(),
											"error", false, "output", output))
									.onErrorResume(e -> Mono.just(Map.of("tool", call.name(), "callId", call.id(),
											"error", true, "output", String.valueOf(e.getMessage())))))
							.flatMap(payload -> context.emit(RunEventType.TOOL_RESULT, actor, payload));
				})
				.then();
	}

	/** An agent with a shell can read the credential from the CLI's environment; it must not end up in the change. */
	private Mono<AgentLoop.Outcome> withoutCredential(UUID runId, String token, AgentLoop.Outcome outcome) {
		return checkout.diff(runId).map(diff -> !diff.contains(token) ? outcome
				: new AgentLoop.Outcome(AgentLoop.Stop.FAILED, "the changes contain the Claude Code credential. Remove "
						+ "it from the working copy, revoke the token and create a new one", outcome.usage(),
						outcome.turns()));
	}

	/** What the run may still spend, for {@code --max-budget-usd}; at least a cent, as the flag needs a positive amount. */
	private BigDecimal remainingUsd(StageContext context) {
		long remaining = maxCostMicroUsd - context.run().usage().costMicroUsd() - context.spent().costMicroUsd();
		return BigDecimal.valueOf(Math.max(remaining, 10_000), 6).setScale(2, RoundingMode.DOWN);
	}

	/**
	 * Writes a scratch file in the container, readable only by the sandbox user. The content travels base64-encoded
	 * in the environment, in chunks well under the kernel's limit per variable.
	 */
	private Mono<Void> write(UUID runId, String environment, String path, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		int chunks = Math.max(1, (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
		String parent = path.substring(0, path.lastIndexOf('/'));
		return Flux.range(0, chunks).concatMap(i -> {
			String data = Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, i * CHUNK_BYTES,
					Math.min(bytes.length, (i + 1) * CHUNK_BYTES)));
			String script = (i == 0 ? "umask 077 && mkdir -p " + shellQuote(parent) + " && " : "")
					+ "printf '%s' \"$AGENTIC_DATA\" | base64 -d " + (i == 0 ? ">" : ">>") + " " + shellQuote(path);
			return sandbox.execLines(runId, environment, script, Map.of("AGENTIC_DATA", data), FILE_TIMEOUT).then();
		}).then();
	}
}
