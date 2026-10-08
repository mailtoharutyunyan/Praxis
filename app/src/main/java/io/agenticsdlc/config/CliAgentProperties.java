package io.agenticsdlc.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Agents on an AI CLI, Claude Code, instead of the API loop (ADR-0008). The AI model connector configured in the UI
 * takes precedence: when it is saved, its engine and token apply and {@code enabled}, {@code token} and {@code model}
 * here are ignored.
 *
 * @param enabled run agents on Claude Code when no model connector is configured in the UI
 * @param token Claude subscription token from {@code claude setup-token}, or an Anthropic API key; passed only to the
 *        CLI's own process, never to the container. Used when the connector has none
 * @param model {@code --model} for every role; empty uses the CLI's default
 * @param binary path of the {@code claude} executable inside sandboxes
 * @param toolsVolume Docker volume holding the CLI (Compose fills {@code agentic-tools}), mounted read-only at
 *        {@code /opt/agentic-tools} in every sandbox; empty for none
 * @param toolsDir host directory holding the CLI, mounted the same way when no volume is set; empty for none
 * @param timeout one CLI call; it is killed after this
 * @param maxTurns {@code --max-turns} of each call
 * @param untrustedTasks also run tasks from Jira, Slack, issues and other untrusted sources on the CLI. Off: they use
 *        the API engine, or wait for a human if no API model is configured
 */
@Validated
@ConfigurationProperties("agentic.agent.cli")
public record CliAgentProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String token,
		@DefaultValue("") String model, @DefaultValue("/opt/agentic-tools/claude") @NotBlank String binary,
		@DefaultValue("") String toolsVolume, @DefaultValue("") String toolsDir,
		@DefaultValue("20m") @NotNull Duration timeout, @DefaultValue("60") @Min(1) int maxTurns,
		@DefaultValue("false") boolean untrustedTasks) {

	@Override
	public String toString() {
		return "CliAgentProperties[enabled=" + enabled + ", token=" + (token.isBlank() ? "" : "***") + ", model=" + model
				+ ", binary=" + binary + ", toolsVolume=" + toolsVolume + ", toolsDir=" + toolsDir + ", timeout=" + timeout
				+ ", maxTurns=" + maxTurns + ", untrustedTasks=" + untrustedTasks + "]";
	}
}
