package io.agenticsdlc.adapter.out.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.config.CliAgentProperties;
import io.agenticsdlc.core.agent.AgentLoop.Outcome;
import io.agenticsdlc.core.agent.AgentLoop.Stop;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.AgentTool;
import io.agenticsdlc.core.agent.ExternalAgent.Access;
import io.agenticsdlc.core.agent.ExternalAgent.Choice;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.agent.ToolException;
import io.agenticsdlc.core.agent.ToolSpec;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.CommandFailedException;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SandboxSpec;
import io.agenticsdlc.support.TestRepos;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class ClaudeCodeAgentTest {

	private static final String TOKEN = "sk-ant-oat01-agent-test-token";

	/** Scratch files land in {@link #files}; the CLI's command answers with {@link #output}. */
	private final class ScriptedSandbox implements Sandbox {

		final Map<String, String> files = new LinkedHashMap<>();
		final List<String> commands = new ArrayList<>();
		final List<Map<String, String>> environments = new ArrayList<>();
		final AtomicBoolean cancelled = new AtomicBoolean();
		Flux<String> output = Flux.empty();
		String memoryFile = "";

		@Override
		public Flux<String> execLines(UUID runId, String command, Map<String, String> env, Duration timeout) {
			commands.add(command);
			environments.add(env);
			if (command.contains("base64 -d")) {
				String path = command.substring(command.lastIndexOf(" '") + 2, command.length() - 1);
				files.merge(path, new String(Base64.getDecoder().decode(env.get("AGENTIC_DATA")), StandardCharsets.UTF_8),
						String::concat);
				return Flux.empty();
			}
			return output.doOnCancel(() -> cancelled.set(true));
		}

		@Override
		public Mono<CommandResult> exec(UUID runId, String command, Duration timeout) {
			commands.add(command);
			String out = command.startsWith("cat ") && command.contains(ClaudeCodeCommand.MEMORY_FILE) ? memoryFile : "";
			return Mono.just(new CommandResult(command, 0, out, false, false, Duration.ZERO));
		}

		@Override
		public Mono<Void> start(UUID runId, SandboxSpec spec) {
			return Mono.empty();
		}

		@Override
		public Mono<String> readFile(UUID runId, String relativePath, int maxBytes) {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Mono<Void> writeFile(UUID runId, String relativePath, String content) {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Mono<Void> destroy(UUID runId) {
			return Mono.empty();
		}
	}

	private final ScriptedSandbox sandbox = new ScriptedSandbox();
	private String diff = "diff --git a/hello.txt b/hello.txt\n-hello\n+hello agents\n";
	private final RepositoryCheckout checkout = new RepositoryCheckout() {
		@Override
		public Mono<CheckoutInfo> checkout(RunView view) {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Mono<String> diff(UUID runId) {
			return Mono.just(diff);
		}

		@Override
		public Mono<Void> remove(UUID runId) {
			return Mono.empty();
		}
	};
	private final RecordingContext recording = new RecordingContext();

	private static CliAgentProperties properties(boolean untrustedTasks) {
		return new CliAgentProperties(true, "", "", "/opt/agentic-tools/claude", "", "", Duration.ofMinutes(5), 30,
				untrustedTasks);
	}

	private ClaudeCodeAgent agent(Optional<ClaudeCodeAgent.Setup> setup, String network, boolean untrustedTasks) {
		return new ClaudeCodeAgent(sandbox, checkout, properties(untrustedTasks), () -> setup, network, 10_000_000,
				JsonMapper.builder().build());
	}

	private static Optional<ClaudeCodeAgent.Setup> setup(String token, boolean apiFallback) {
		return Optional.of(new ClaudeCodeAgent.Setup(token, "claude-opus-5-5", Map.of("reviewer", "claude-haiku-5-5"),
				apiFallback));
	}

	private static Task task(TaskOrigin origin, Trust trust) {
		Task t = TestRepos.runFor("https://github.com/acme/shop.git").task();
		return new Task(t.id(), origin, "SHOP-1", t.title(), t.description(), t.repository(), null, trust,
				t.requestedBy(), null, t.createdAt());
	}

	@Test
	void untrustedTasksNeverRunOnTheCli() {
		Task jira = task(TaskOrigin.JIRA, Trust.UNTRUSTED);
		Task prompt = task(TaskOrigin.PROMPT, Trust.TRUSTED);

		assertThat(agent(setup(TOKEN, true), "agentic-sandbox", false).choose(jira)).isEqualTo(Choice.LOOP);
		assertThat(agent(setup(TOKEN, false), "agentic-sandbox", false).choose(jira))
				.isInstanceOfSatisfying(Choice.Unavailable.class, u -> assertThat(u.reason())
						.contains("JIRA", "untrusted", "agentic.agent.cli.untrusted-tasks", "API key"));
		assertThat(agent(setup(TOKEN, false), "agentic-sandbox", true).choose(jira)).isEqualTo(Choice.EXTERNAL);
		assertThat(agent(setup(TOKEN, false), "agentic-sandbox", false).choose(prompt)).isEqualTo(Choice.EXTERNAL);
	}

	@Test
	void theCliNeedsANetworkAndAToken() {
		Task prompt = task(TaskOrigin.PROMPT, Trust.TRUSTED);
		assertThat(agent(Optional.empty(), "none", false).choose(prompt)).isEqualTo(Choice.LOOP);
		assertThat(agent(setup(TOKEN, true), "none", false).choose(prompt))
				.isInstanceOfSatisfying(Choice.Unavailable.class, u -> assertThat(u.reason())
						.contains("agentic.sandbox.network=none", "api.anthropic.com"));
		assertThat(agent(setup("", true), "agentic-sandbox", false).choose(prompt))
				.isInstanceOfSatisfying(Choice.Unavailable.class, u -> assertThat(u.reason()).contains("claude setup-token"));
	}

	@Test
	void runsTheCliWithItsBriefInFilesAndTheTokenOnlyInItsEnvironment() {
		sandbox.output = Flux.just(
				"{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"claude-haiku-5-5\"}",
				"{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"text\",\"text\":\"Looks right.\"}],"
						+ "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":1,"
						+ "\"result\":\"Looks right.\\nVERDICT: APPROVE\",\"total_cost_usd\":0.01,"
						+ "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}");

		Outcome outcome = agent(setup(TOKEN, false), "agentic-sandbox", false).run(recording.context, AgentRole.REVIEWER,
				Access.READ_ONLY, "agent:reviewer", "Review it.", "The task <task>x</task>", 1_000_000).block();

		assertThat(outcome.stop()).isEqualTo(Stop.COMPLETED);
		assertThat(outcome.finalText()).endsWith("VERDICT: APPROVE");
		assertThat(outcome.usage().costMicroUsd()).isEqualTo(10_000);
		String cli = sandbox.commands.stream().filter(c -> c.contains("exec '/opt/agentic-tools/claude'")).findFirst()
				.orElseThrow();
		assertThat(cli).contains("'--model' 'claude-haiku-5-5'", "'--tools' 'Read,Glob,Grep'", "'--max-turns' '30'",
				"'--max-budget-usd' '10.00'");
		assertThat(sandbox.commands).allSatisfy(c -> assertThat(c).doesNotContain(TOKEN));
		assertThat(sandbox.environments).filteredOn(env -> env.containsValue(TOKEN)).singleElement()
				.satisfies(env -> assertThat(env).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", TOKEN));
		String dir = sandbox.files.keySet().iterator().next().replace("/system.md", "");
		assertThat(dir).startsWith(ClaudeCodeCommand.SCRATCH + "/");
		assertThat(sandbox.files).containsEntry(dir + "/brief.md", "The task <task>x</task>")
				.containsEntry(dir + "/changes.diff", diff);
		assertThat(sandbox.files.get(dir + "/system.md")).startsWith("Review it.").contains("show_diff is the file "
				+ dir + "/changes.diff").doesNotContain(ClaudeCodeCommand.MEMORY_FILE);
		assertThat(sandbox.commands.getLast()).isEqualTo("rm -rf '" + dir + "'");
		assertThat(recording.of(RunEventType.AGENT_MESSAGE)).singleElement()
				.satisfies(e -> assertThat(e.payload()).containsEntry("model", "claude-code/claude-haiku-5-5"));
	}

	@Test
	void factsTheCliWritesAreStoredThroughTheRememberTool() {
		sandbox.output = Flux.just("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":2,"
				+ "\"result\":\"Spec ready.\",\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
		sandbox.memoryFile = """
				{"fact": "UI tests run with npm test in ui/", "citations": [{"path": "ui/package.json", "line": 8}]}
				not json at all
				{"fact": "Cited nowhere", "citations": []}
				""";
		List<ToolCall> stored = new ArrayList<>();
		AgentTool remember = new AgentTool() {
			@Override
			public ToolSpec spec() {
				return new ToolSpec("remember", "Save a fact.", "{\"type\":\"object\"}");
			}

			@Override
			public Mono<String> execute(UUID runId, ToolCall call) {
				stored.add(call);
				return call.arguments().get("citations") instanceof List<?> l && !l.isEmpty() ? Mono.just("Saved.")
						: Mono.error(new ToolException("citations must list at least one {path, line}"));
			}

			@Override
			public boolean mutates() {
				return false;
			}
		};

		Outcome outcome = agent(setup(TOKEN, false), "agentic-sandbox", false).run(recording.context, AgentRole.PLANNER,
				Access.READ_ONLY, "agent:planner", "Plan it.", "The task", 1_000_000, "main", remember).block();

		assertThat(outcome.stop()).isEqualTo(Stop.COMPLETED);
		String dir = sandbox.files.keySet().iterator().next().replace("/system.md", "");
		assertThat(sandbox.files.get(dir + "/system.md")).contains("write it to " + dir + "/" + ClaudeCodeCommand.MEMORY_FILE);
		String cli = sandbox.commands.stream().filter(c -> c.contains("exec '/opt/agentic-tools/claude'")).findFirst()
				.orElseThrow();
		// A read-only call may write exactly one file, outside the workspace.
		assertThat(cli).contains("'--tools' 'Read,Glob,Grep,Write'", "'Edit(/" + dir + "/" + ClaudeCodeCommand.MEMORY_FILE + ")'")
				.doesNotContain("'Edit(./**)'");
		assertThat(stored).extracting(c -> c.arguments().get("fact")).containsExactly("UI tests run with npm test in ui/",
				"Cited nowhere");
		assertThat(recording.of(RunEventType.TOOL_RESULT)).extracting(e -> e.payload().get("error")).containsExactly(false, true);
		assertThat(sandbox.commands.getLast()).isEqualTo("rm -rf '" + dir + "'");
	}

	@Test
	void triageGetsNoToolsOneTurnAndNoDiff() {
		sandbox.output = Flux.just("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":1,"
				+ "\"result\":\"RISK: LOW\\nRATIONALE: tiny\"}");
		Outcome outcome = agent(setup(TOKEN, false), "agentic-sandbox", false).run(recording.context, AgentRole.TRIAGE,
				Access.NONE, "agent:triage", "Rate it.", "task", 1_000).block();
		assertThat(outcome.finalText()).startsWith("RISK: LOW");
		assertThat(sandbox.commands).anySatisfy(c -> assertThat(c).contains("'--tools' ''", "'--max-turns' '1'"));
		assertThat(sandbox.files.keySet()).noneMatch(path -> path.endsWith("changes.diff"));
		assertThat(sandbox.files.values()).noneMatch(content -> content.contains("show_diff"));
	}

	@Test
	void anOverspendingCliIsStopped() {
		AtomicBoolean pastTheBudget = new AtomicBoolean();
		sandbox.output = Flux.concat(Flux.just(
				"{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[],\"usage\":{\"input_tokens\":600}}}",
				"{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"content\":[],\"usage\":{\"input_tokens\":600}}}"),
				Flux.defer(() -> {
					pastTheBudget.set(true);
					return Flux.just("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"late\"}");
				}));

		Outcome outcome = agent(setup(TOKEN, false), "agentic-sandbox", false).run(recording.context, AgentRole.CODER,
				Access.FULL, "agent:coder", "Code it.", "task", 1_000).block();

		assertThat(outcome.stop()).isEqualTo(Stop.BUDGET_EXHAUSTED);
		assertThat(outcome.usage().inputTokens()).isEqualTo(1_200);
		assertThat(sandbox.cancelled).isTrue();
		assertThat(pastTheBudget).isFalse();
		assertThat(recording.context.spent().inputTokens()).isEqualTo(1_200);
	}

	@Test
	void aFailedCliIsAFailedOutcomeWithItsErrorRedacted() {
		sandbox.output = Flux.error(new CommandFailedException(new CommandResult("claude", 1,
				"Invalid bearer token " + TOKEN, false, false, Duration.ofSeconds(2))));
		Outcome outcome = agent(setup(TOKEN, false), "agentic-sandbox", false).run(recording.context, AgentRole.CODER,
				Access.FULL, "agent:coder", "Code it.", "task", 1_000).block();
		assertThat(outcome.stop()).isEqualTo(Stop.FAILED);
		assertThat(outcome.finalText()).isEqualTo("Claude Code exited with code 1: Invalid bearer token [REDACTED]");
	}

	@Test
	void aChangeThatContainsTheCredentialFails() {
		diff = "diff --git a/leak.txt b/leak.txt\n+" + TOKEN + "\n";
		sandbox.output = Flux.just("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\"}");
		Outcome outcome = agent(setup(TOKEN, false), "agentic-sandbox", false).run(recording.context, AgentRole.CODER,
				Access.FULL, "agent:coder", "Code it.", "task", 1_000).block();
		assertThat(outcome.stop()).isEqualTo(Stop.FAILED);
		assertThat(outcome.finalText()).contains("contain the Claude Code credential").doesNotContain(TOKEN);
	}
}
