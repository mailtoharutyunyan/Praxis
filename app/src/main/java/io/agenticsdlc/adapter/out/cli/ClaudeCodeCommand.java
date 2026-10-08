package io.agenticsdlc.adapter.out.cli;

import static io.agenticsdlc.core.workspace.WorkspacePath.shellQuote;

import io.agenticsdlc.core.agent.ExternalAgent.Access;
import io.agenticsdlc.core.stage.TestPaths;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One headless Claude Code call ({@code claude -p}, ADR-0008): the shell command run in the sandbox, and the
 * environment set for that command alone. The credential is only ever in {@link #env}: the command line shows up in
 * the container's process list and in logs.
 * <p>
 * The call reads its brief from {@code brief.md} on standard input and its instructions from {@code system.md}, both
 * in its scratch directory under {@value #SCRATCH}, outside the workspace. Nothing in the repository configures the
 * CLI: project settings, hooks and MCP servers are not loaded. It runs unattended, so every tool use that is not
 * allowed for its {@link Access} is denied rather than asked about.
 *
 * @param line shell command, run in the workspace
 * @param env variables for this command only, including the credential
 */
record ClaudeCodeCommand(String line, Map<String, String> env) {

	/** Scratch files of the calls: in the container's /tmp, never in the workspace, so never part of a change. */
	static final String SCRATCH = "/tmp/.agentic-cli";
	/** Claude Code's configuration and session files, shared by a run's calls. */
	static final String CONFIG = SCRATCH + "/config";
	/** Prefix of Anthropic API keys; anything else is taken for a subscription token from {@code claude setup-token}. */
	static final String API_KEY_PREFIX = "sk-ant-api";

	/**
	 * @param dir this call's scratch directory, under {@value #SCRATCH}
	 * @param model {@code --model}; empty for the CLI's default
	 * @param maxBudgetUsd {@code --max-budget-usd}: what the run may still spend; null for no limit
	 */
	static ClaudeCodeCommand of(String binary, String token, String model, int maxTurns, Access access, String dir,
			BigDecimal maxBudgetUsd) {
		List<String> args = new ArrayList<>(List.of(binary, "-p", "--output-format", "stream-json", "--verbose",
				"--max-turns", String.valueOf(access == Access.NONE ? 1 : maxTurns)));
		if (!model.isBlank()) {
			args.addAll(List.of("--model", model));
		}
		args.addAll(List.of("--append-system-prompt-file", dir + "/system.md",
				// Only the (empty) user settings and the files written here: the repository cannot add hooks, permission
				// rules or MCP servers.
				"--setting-sources", "user", "--settings", dir + "/settings.json", "--mcp-config", dir + "/mcp.json",
				"--strict-mcp-config",
				"--permission-mode", "dontAsk", "--permission-prompts", "none",
				"--tools", String.join(",", tools(access))));
		if (maxBudgetUsd != null) {
			args.addAll(List.of("--max-budget-usd", maxBudgetUsd.toPlainString()));
		}
		List<String> allowed = allowedTools(access, dir);
		if (!allowed.isEmpty()) {
			// Variadic, so last.
			args.add("--allowedTools");
			args.addAll(allowed);
		}
		String missing = "Claude Code is not installed at " + binary + " in this sandbox (see the README, Agents on an "
				+ "AI CLI)";
		String line = "[ -x " + shellQuote(binary) + " ] || { echo " + shellQuote(missing) + " >&2; exit 127; }; "
				+ "printf '%s' " + shellQuote("{\"disableAllHooks\":true}") + " > " + shellQuote(dir + "/settings.json")
				+ " && printf '%s' " + shellQuote("{\"mcpServers\":{}}") + " > " + shellQuote(dir + "/mcp.json")
				+ " && exec " + args.stream().map(arg -> shellQuote(arg)).collect(Collectors.joining(" "))
				+ " < " + shellQuote(dir + "/brief.md");
		return new ClaudeCodeCommand(line, environment(token));
	}

	/** The built-in tools the call has at all; anything else, including subagents and web access, is unavailable. */
	static List<String> tools(Access access) {
		return switch (access) {
			case NONE -> List.of();
			case READ_ONLY -> List.of("Read", "Glob", "Grep");
			case TESTS_ONLY -> List.of("Read", "Glob", "Grep", "Edit", "Write");
			case FULL -> List.of("Read", "Glob", "Grep", "Edit", "Write", "Bash");
		};
	}

	/**
	 * Permission rules for the tools that need one in {@code dontAsk} mode; reading the working directory needs none.
	 * Edit rules govern every file-writing tool. The test writer may only edit paths matching {@link TestPaths#globs},
	 * which the stage checks again afterwards.
	 */
	static List<String> allowedTools(Access access, String dir) {
		String scratch = "Read(/" + dir + "/**)";
		return switch (access) {
			case NONE -> List.of();
			case READ_ONLY -> List.of(scratch);
			case TESTS_ONLY -> {
				List<String> rules = new ArrayList<>(List.of(scratch));
				TestPaths.globs().forEach(glob -> rules.add("Edit(" + glob + ")"));
				yield List.copyOf(rules);
			}
			case FULL -> List.of(scratch, "Edit(./**)", "Bash");
		};
	}

	/** Credential, scratch configuration, no telemetry or updates, and credentials scrubbed from tool subprocesses. */
	static Map<String, String> environment(String token) {
		Map<String, String> env = new LinkedHashMap<>();
		env.put(token.startsWith(API_KEY_PREFIX) ? "ANTHROPIC_API_KEY" : "CLAUDE_CODE_OAUTH_TOKEN", token);
		env.put("CLAUDE_CONFIG_DIR", CONFIG);
		env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
		env.put("DISABLE_TELEMETRY", "1");
		env.put("DISABLE_ERROR_REPORTING", "1");
		env.put("DISABLE_AUTOUPDATER", "1");
		env.put("DISABLE_UPDATES", "1");
		env.put("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB", "1");
		return env;
	}

	/** Never prints the credential. */
	@Override
	public String toString() {
		return "ClaudeCodeCommand[" + line + "]";
	}
}
