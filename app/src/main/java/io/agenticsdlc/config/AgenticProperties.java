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
		@Valid @NotNull Models models, @Valid @NotNull Agent agent, @Valid @NotNull Jira jira, @Valid @NotNull Ui ui,
		@Valid @NotNull @DefaultValue Mcp mcp) {

	/**
	 * @param enabled run the background worker on this instance (disable for API-only replicas)
	 * @param concurrency runs processed in parallel by this instance
	 * @param pollInterval idle wait before looking for claimable runs again
	 * @param lease how long a claimed run stays reserved without a heartbeat
	 * @param nodeId stable name of this instance; runs stay on the node holding their workspace. Empty uses the host
	 *        name, which suits one instance per host or a Kubernetes StatefulSet with a persistent workspace volume
	 * @param nodeTimeout after this long without a heartbeat a node is presumed dead and its runs move to other nodes
	 * @param drainTimeout on shutdown, how long in-flight stages may finish before they are cancelled and handed over
	 */
	public record Worker(@DefaultValue("true") boolean enabled, @DefaultValue("4") @Min(1) @Max(64) int concurrency,
			@DefaultValue("1s") @NotNull Duration pollInterval, @DefaultValue("60s") @NotNull Duration lease,
			@DefaultValue("") String nodeId, @DefaultValue("90s") @NotNull Duration nodeTimeout,
			@DefaultValue("20s") @NotNull Duration drainTimeout) {
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
	 * @param network container network: {@code none} (default) isolates fully; to download dependencies, attach an
	 *        internal network whose only way out is {@code egressProxy} (see {@code dev/egress}). {@code bridge} gives
	 *        unrestricted egress, including cloud metadata and internal hosts; {@code host} is refused
	 * @param egressProxy allowlisting HTTP proxy, e.g. {@code http://egress:3128}, exported to builds as
	 *        {@code HTTP(S)_PROXY}, JVM proxy properties and a Maven global settings file; empty for none
	 * @param noProxy hosts reached without the proxy
	 * @param user {@code uid:gid} to run as; empty uses the owner of the workspace root so files stay writable. Never
	 *        root: the workspace is bind-mounted from the host
	 * @param maxOutputChars per command; longer output keeps its head and tail
	 */
	public record Sandbox(@DefaultValue("false") boolean enabled, @NotNull java.nio.file.Path workspaceRoot,
			@DefaultValue("") String dockerHost, @DefaultValue("none") @NotBlank String network,
			@DefaultValue("") String egressProxy, @DefaultValue("localhost,127.0.0.1") String noProxy,
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
			@DefaultValue("false") boolean draftPullRequests, @DefaultValue("2m") @NotNull Duration pullRequestPollInterval,
			@Valid @NotNull @DefaultValue Feedback feedback) {
	}

	/**
	 * Revisions of open pull requests from code host webhooks (review comments and failed CI), see README.
	 *
	 * @param mention how reviewers address the bot in a pull request comment
	 * @param githubSecret secret of the GitHub webhook ({@code X-Hub-Signature-256}); empty disables the endpoint
	 * @param gitlabToken secret token of the GitLab webhook ({@code X-Gitlab-Token}); empty disables the endpoint
	 * @param maxRevisions revision rounds per run, from any source
	 * @param maxCiFixes revision rounds per run triggered by failed CI
	 * @param runLinkBase prefix for links to a run in replies, e.g. {@code https://agentic.example.com/#/runs/}
	 */
	public record Feedback(@DefaultValue("@agentic-sdlc") @NotBlank String mention, @DefaultValue("") String githubSecret,
			@DefaultValue("") String gitlabToken, @DefaultValue("5") @Min(1) int maxRevisions,
			@DefaultValue("3") @Min(0) int maxCiFixes, @DefaultValue("") String runLinkBase) {
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
	 * @param modelTimeout one model call, including the provider SDK's own retries
	 * @param modelRetries further attempts after a transient failure (rate limit, overload, 5xx, timeout)
	 * @param testsFirst write failing tests for the change before implementing it (first round of a run)
	 */
	public record Agent(@DefaultValue("true") boolean enabled, @DefaultValue("60") @Min(1) int maxTurns,
			@DefaultValue("12000") @Min(500) int maxToolResultChars, @DefaultValue("3") @Min(2) int maxRepeats,
			@DefaultValue("16000") @Min(256) int maxOutputTokens, @DefaultValue("10m") @NotNull Duration modelTimeout,
			@DefaultValue("4") @Min(0) @Max(10) int modelRetries, @DefaultValue("true") boolean testsFirst) {
	}

	/**
	 * Jira intake and status comments.
	 *
	 * @param deployment {@code cloud} (REST v3, ADF, email + API token) or {@code data-center} (REST v2, PAT)
	 * @param email Atlassian account email for Basic auth; blank sends {@code apiToken} as a Bearer PAT (Data Center)
	 * @param webhookSecret secret of the Jira admin webhook; requests must carry a matching {@code X-Hub-Signature}
	 * @param automationToken shared secret Jira Automation "Send web request" rules send in
	 *        {@code X-Agentic-Webhook-Token}
	 * @param triggerLabel issues with this label start a run when created with it or when it is added
	 * @param projects Jira project key → repository the work goes to
	 * @param runLinkBase prefix for links to a run in comments (the UI), e.g. https://agentic.example.com/runs/
	 */
	public record Jira(@DefaultValue("false") boolean enabled,
			@DefaultValue("cloud") @jakarta.validation.constraints.Pattern(regexp = "cloud|data-center") String deployment,
			@DefaultValue("") String baseUrl,
			@DefaultValue("") String email, @DefaultValue("") String apiToken, @DefaultValue("") String webhookSecret,
			@DefaultValue("") String automationToken, @DefaultValue("agentic") @NotBlank String triggerLabel,
			@DefaultValue({}) java.util.Map<String, @Valid JiraProject> projects, @DefaultValue("") String runLinkBase,
			@DefaultValue("30s") @NotNull Duration updateInterval) {
	}

	public record JiraProject(@NotNull io.agenticsdlc.core.domain.ScmKind kind, @NotNull java.net.URI cloneUrl,
			@DefaultValue("") String baseBranch) {
	}

	/**
	 * Web UI served from this application.
	 *
	 * @param authMode {@code oidc} (authorization code + PKCE against {@code issuer}) or {@code dev} (paste a token)
	 * @param clientId public OIDC client registered for the UI
	 */
	public record Ui(@DefaultValue("oidc") @NotBlank String authMode, @DefaultValue("") String issuer,
			@DefaultValue("") String clientId, @DefaultValue("openid profile") String scope) {
	}

	/**
	 * MCP server for AI clients (ADR-0005); its endpoint is {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}.
	 *
	 * @param runLinkBase prefix for links to a run in the UI, e.g. {@code https://agentic.example.com/#/runs/}
	 * @param resource this server's public MCP URL, advertised to clients in the OAuth protected resource metadata;
	 *        empty to derive it from the request
	 */
	public record Mcp(@DefaultValue("") String runLinkBase, @DefaultValue("") String resource) {
	}
}
