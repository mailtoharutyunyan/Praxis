package io.agenticsdlc.adapter.out.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.agent.ExternalAgent.Access;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ClaudeCodeCommandTest {

	private static final String TOKEN = "sk-ant-oat01-never-in-the-command-line";
	private static final String DIR = "/tmp/.agentic-cli/call-1";

	private static ClaudeCodeCommand command(Access access) {
		return ClaudeCodeCommand.of("/opt/agentic-tools/claude", TOKEN, "claude-opus-5-5", 40, access, DIR,
				new BigDecimal("4.25"));
	}

	@Test
	void theCredentialIsOnlyInTheEnvironment() {
		for (Access access : Access.values()) {
			ClaudeCodeCommand command = command(access);
			assertThat(command.line()).doesNotContain(TOKEN);
			assertThat(command.toString()).doesNotContain(TOKEN);
			assertThat(command.env()).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", TOKEN).doesNotContainKey("ANTHROPIC_API_KEY");
		}
		assertThat(ClaudeCodeCommand.environment("sk-ant-api03-key")).containsEntry("ANTHROPIC_API_KEY", "sk-ant-api03-key")
				.doesNotContainKey("CLAUDE_CODE_OAUTH_TOKEN");
	}

	@Test
	void runsHeadlessWithStreamedOutputAndNothingFromTheRepository() {
		String line = command(Access.FULL).line();
		assertThat(line).startsWith("[ -x '/opt/agentic-tools/claude' ] || { echo 'Claude Code is not installed")
				.contains("exec '/opt/agentic-tools/claude' '-p' '--output-format' 'stream-json' '--verbose'",
						"'--max-turns' '40'", "'--model' 'claude-opus-5-5'",
						"'--append-system-prompt-file' '" + DIR + "/system.md'",
						"'--setting-sources' 'user' '--settings' '" + DIR + "/settings.json'",
						"'--mcp-config' '" + DIR + "/mcp.json' '--strict-mcp-config'",
						"'--permission-mode' 'dontAsk' '--permission-prompts' 'none'", "'--max-budget-usd' '4.25'",
						"{\"disableAllHooks\":true}", "{\"mcpServers\":{}}")
				.endsWith("< '" + DIR + "/brief.md'");
		assertThat(command(Access.FULL).env()).containsEntry("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
				.containsEntry("DISABLE_TELEMETRY", "1").containsEntry("DISABLE_AUTOUPDATER", "1")
				.containsEntry("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB", "1")
				.containsEntry("CLAUDE_CONFIG_DIR", ClaudeCodeCommand.CONFIG);
		assertThat(ClaudeCodeCommand.of("/c", TOKEN, "", 5, Access.READ_ONLY, DIR, null).line())
				.doesNotContain("--model", "--max-budget-usd");
	}

	@Test
	void eachAccessGetsOnlyItsToolsAndPermissions() {
		assertThat(command(Access.NONE).line()).contains("'--tools' ''", "'--max-turns' '1'").doesNotContain("--allowedTools");
		assertThat(command(Access.READ_ONLY).line()).contains("'--tools' 'Read,Glob,Grep'",
				"'--allowedTools' 'Read(//tmp/.agentic-cli/call-1/**)' <").doesNotContain("Edit", "Bash");
		assertThat(command(Access.TESTS_ONLY).line()).contains("'--tools' 'Read,Glob,Grep,Edit,Write'",
				"'Edit(**/test/**)'", "'Edit(**/*Test.java)'", "'Edit(**/*.spec.ts)'").doesNotContain("Bash", "'Edit(./**)'");
		assertThat(command(Access.FULL).line()).contains("'--tools' 'Read,Glob,Grep,Edit,Write,Bash'", "'Edit(./**)' 'Bash'");
	}
}
