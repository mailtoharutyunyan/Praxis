package io.agenticsdlc.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** All application settings under {@code agentic.*}. Validated at startup: a bad value fails fast. */
@Validated
@ConfigurationProperties("agentic")
public record AgenticProperties(@Valid @NotNull Worker worker, @Valid @NotNull Limits limits,
		@Valid @NotNull Gates gates, @Valid @NotNull Security security, @Valid @NotNull Events events,
		@Valid @NotNull StubStages stubStages, @Valid @NotNull Sandbox sandbox, @Valid @NotNull Scm scm,
		@Valid @NotNull Models models, @Valid @NotNull Agent agent, @Valid @NotNull Jira jira) {

	/**
	 * @param enabled run the background worker on this instance (disable for API-only replicas)
	 * @param concurrency runs processed in parallel by this instance
	 * @param pollInterval idle wait before looking for claimable runs again
	 * @param lease how long a claimed run stays reserved without a heartbeat
	 */
	public record Worker(@DefaultValue("true") boolean enabled, @DefaultValue("4") @Min(1) @Max(64) int concurrency,
			@DefaultValue("1s") @NotNull Duration pollInterval, @DefaultValue("60s") @NotNull Duration lease) {
	}

	public record Limits(@DefaultValue("3") @Min(0) int maxFixIterations, @DefaultValue("2") @Min(0) int maxReviewLoops,
			@DefaultValue("2000000") @Min(1) long maxTokens,
			@DefaultValue("10.00") @NotNull @DecimalMin("0.01") BigDecimal maxCostUsd,
			@DefaultValue("30m") @NotNull Duration stageTimeout) {

		public long maxCostMicroUsd() {
			return maxCostUsd.movePointRight(6).longValueExact();
		}
	}

	/** @param forbidSelfApproval four-eyes rule: a task's requester may not decide its gates */
	public record Gates(@DefaultValue("false") boolean forbidSelfApproval) {
	}

	/**
	 * @param rolesClaim JWT claim holding the user's roles; dotted paths reach nested claims
	 *        (Keycloak: {@code realm_access.roles})
	 * @param corsAllowedOrigins browser origins allowed to call the API (the future web UI)
	 */
	public record Security(@DefaultValue("roles") @NotBlank String rolesClaim,
			@DefaultValue({}) List<String> corsAllowedOrigins) {
	}

	/**
	 * @param fallbackPoll live streams re-read the log at least this often, covering lost notifications
	 * @param keepAlive SSE comment interval that keeps proxies from closing idle streams
	 */
	public record Events(@DefaultValue("5s") @NotNull Duration fallbackPoll,
			@DefaultValue("15s") @NotNull Duration keepAlive) {
	}

	/** Placeholder stage handlers until the real agent stages exist. Never enable in production. */
	public record StubStages(@DefaultValue("false") boolean enabled) {
	}

	/**
	 * Docker sandbox for builds and agent commands (ADR-0003).
	 *
	 * @param enabled register the real workspace stages (context preparation, verification)
	 * @param workspaceRoot host directory holding each run's checkout ({@code <root>/<runId>/repo}) and git metadata
	 *        ({@code <root>/<runId>/git}, never mounted into the sandbox)
	 * @param dockerHost Docker endpoint; empty uses {@code DOCKER_HOST} or the platform default socket
	 * @param network container network mode; {@code none} isolates fully but then dependencies cannot be downloaded
	 * @param user {@code uid:gid} to run as; empty uses the owner of the workspace root so files stay writable
	 * @param maxOutputChars per command; longer output keeps its head and tail
	 */
	public record Sandbox(@DefaultValue("false") boolean enabled, @NotNull java.nio.file.Path workspaceRoot,
			@DefaultValue("") String dockerHost, @DefaultValue("bridge") @NotBlank String network,
			@DefaultValue("4GB") @NotNull org.springframework.util.unit.DataSize memory,
			@DefaultValue("2") @DecimalMin("0.1") double cpus, @DefaultValue("1024") @Min(64) long pidsLimit,
			@DefaultValue("") String user, @DefaultValue("20m") @NotNull Duration commandTimeout,
			@DefaultValue("10m") @NotNull Duration imagePullTimeout,
			@DefaultValue("32000") @Min(1000) int maxOutputChars) {
	}

	/**
	 * Source control access from the host.
	 *
	 * @param allowedHosts hosts runs may clone from (SSRF guard)
	 * @param tokens access token per host, e.g. {@code agentic.scm.tokens.[github.com]=${GITHUB_TOKEN}}; never logged
	 * @param mirrors URL prefix rewrites like git's {@code insteadOf}, e.g. to an internal mirror
	 * @param cloneDepth 0 for full history; shallow clones are much faster on large repositories
	 * @param apiUrls REST API base per host when not the provider default (GitHub Enterprise, self-managed GitLab)
	 * @param authorName commit author for agent changes
	 * @param draftPullRequests open pull requests as drafts
	 */
	public record Scm(@DefaultValue({ "github.com", "gitlab.com", "bitbucket.org", "dev.azure.com" }) List<String> allowedHosts,
			@DefaultValue({}) java.util.Map<String, String> tokens, @DefaultValue({}) java.util.Map<String, String> mirrors,
			@DefaultValue("1") @Min(0) int cloneDepth, @DefaultValue({}) java.util.Map<String, String> apiUrls,
			@DefaultValue("Agentic SDLC") @NotBlank String authorName,
			@DefaultValue("agentic-sdlc@noreply.invalid") @NotBlank String authorEmail,
			@DefaultValue("false") boolean draftPullRequests, @DefaultValue("2m") @NotNull Duration pullRequestPollInterval) {
	}

	/**
	 * LLM providers and which model serves each role (ADR-0001). Secrets come from the environment only.
	 *
	 * @param providers named connections, e.g. {@code anthropic}, {@code openai}, {@code ollama}
	 * @param roles keyed by role name: {@code triage}, {@code planner}, {@code coder}, {@code reviewer}
	 * @param pricing USD per million tokens, keyed by model id; models without pricing are tracked at zero cost
	 */
	public record Models(@DefaultValue({}) java.util.Map<String, @Valid Provider> providers,
			@DefaultValue({}) java.util.Map<String, @Valid RoleModel> roles,
			@DefaultValue({}) java.util.Map<String, @Valid Pricing> pricing) {
	}

	/**
	 * @param type {@code anthropic}, {@code openai}, {@code azure-openai}, {@code ollama}, {@code bedrock} or
	 *        {@code google-genai}
	 * @param region AWS region for Bedrock
	 * @param deployment Azure OpenAI deployment name
	 */
	public record Provider(@NotBlank String type, @DefaultValue("") String apiKey, @DefaultValue("") String baseUrl,
			@DefaultValue("") String region, @DefaultValue("") String deployment) {
	}

	/**
	 * @param effort provider reasoning effort where supported ({@code low} … {@code max}); empty keeps the default
	 */
	public record RoleModel(@NotBlank String provider, @NotBlank String model,
			@DefaultValue("16000") @Min(256) int maxOutputTokens, @DefaultValue("") String effort) {
	}

	public record Pricing(@NotNull BigDecimal input, @NotNull BigDecimal output,
			@DefaultValue("0") BigDecimal cacheRead, @DefaultValue("0") BigDecimal cacheWrite) {
	}

	/**
	 * Agent loop limits (ADR-0003).
	 *
	 * @param enabled register the model-driven stages (triage, spec, implement, review); requires the sandbox
	 * @param maxTurns model calls per stage
	 * @param maxToolResultChars tool output shown to the model; the tail is kept
	 * @param maxRepeats identical consecutive tool calls before the loop counts as stuck
	 */
	public record Agent(@DefaultValue("true") boolean enabled, @DefaultValue("60") @Min(1) int maxTurns,
			@DefaultValue("12000") @Min(500) int maxToolResultChars, @DefaultValue("3") @Min(2) int maxRepeats,
			@DefaultValue("16000") @Min(256) int maxOutputTokens) {
	}

	/**
	 * Jira intake and status comments (Jira Cloud REST v3).
	 *
	 * @param email Atlassian account email for Basic auth; blank sends {@code apiToken} as a Bearer PAT (Data Center)
	 * @param webhookSecret secret of the Jira admin webhook; requests must carry a matching {@code X-Hub-Signature}
	 * @param automationToken shared secret Jira Automation "Send web request" rules send in
	 *        {@code X-Agentic-Webhook-Token}
	 * @param triggerLabel issues with this label start a run when created with it or when it is added
	 * @param projects Jira project key → repository the work goes to
	 * @param runLinkBase prefix for links to a run in comments (the UI), e.g. https://agentic.example.com/runs/
	 */
	public record Jira(@DefaultValue("false") boolean enabled, @DefaultValue("") String baseUrl,
			@DefaultValue("") String email, @DefaultValue("") String apiToken, @DefaultValue("") String webhookSecret,
			@DefaultValue("") String automationToken, @DefaultValue("agentic") @NotBlank String triggerLabel,
			@DefaultValue({}) java.util.Map<String, @Valid JiraProject> projects, @DefaultValue("") String runLinkBase,
			@DefaultValue("30s") @NotNull Duration updateInterval) {
	}

	public record JiraProject(@NotNull io.agenticsdlc.core.domain.ScmKind kind, @NotNull java.net.URI cloneUrl,
			@DefaultValue("") String baseBranch) {
	}
}
