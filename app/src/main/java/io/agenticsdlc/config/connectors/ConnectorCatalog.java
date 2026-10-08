package io.agenticsdlc.config.connectors;

import java.util.List;
import java.util.Optional;

/**
 * The connectors the app knows and the fields of each, so the onboarding wizard and the settings page can render
 * forms without hard-coding them (ADR-0007). Secret fields are written but never read back.
 */
public final class ConnectorCatalog {

	public static final String APP = "app";
	public static final String GIT = "git";
	public static final String MODELS = "models";
	public static final String JIRA = "jira";
	public static final String SLACK = "slack";
	public static final String WEBHOOKS = "webhooks";

	/**
	 * @param type text, url, secret, select, list
	 * @param options for select
	 * @param itemFields for list: the fields of each item; {@code keyField} names the item field that identifies it
	 */
	public record Field(String name, String label, String type, boolean required, String help, String defaultValue,
			List<String> options, List<Field> itemFields, String keyField) {

		static Field text(String name, String label, boolean required, String help, String defaultValue) {
			return new Field(name, label, "text", required, help, defaultValue, List.of(), List.of(), null);
		}

		static Field url(String name, String label, boolean required, String help) {
			return new Field(name, label, "url", required, help, null, List.of(), List.of(), null);
		}

		static Field secret(String name, String label, boolean required, String help) {
			return new Field(name, label, "secret", required, help, null, List.of(), List.of(), null);
		}

		static Field select(String name, String label, List<String> options, String defaultValue, String help) {
			return new Field(name, label, "select", true, help, defaultValue, options, List.of(), null);
		}

		static Field list(String name, String label, boolean required, String help, String keyField, List<Field> items) {
			return new Field(name, label, "list", required, help, null, List.of(), items, keyField);
		}
	}

	public record Definition(String id, String title, String description, boolean required, boolean testable,
			List<Field> fields) {
	}

	private static final List<String> SCM_KINDS = List.of("GITHUB", "GITLAB", "BITBUCKET", "AZURE_DEVOPS");

	public static final List<Definition> ALL = List.of(
			new Definition(APP, "This app", "Where people open the app; links in Jira, Slack and pull requests point here.",
					true, false, List.of(Field.url("publicUrl", "Public URL", true, "e.g. http://localhost:8080 or "
							+ "https://agentic.example.com"))),
			new Definition(GIT, "Code hosts", "Access tokens for the repositories agents clone, push and open pull "
					+ "requests in. Tokens stay on the server; sandboxes never see them.", true, true, List.of(
							Field.list("hosts", "Hosts", true, "One entry per host", "host", List.of(
									Field.select("kind", "Provider", SCM_KINDS, "GITHUB", null),
									Field.text("host", "Host", true, "github.com, gitlab.com, bitbucket.org, dev.azure.com "
											+ "or your server", "github.com"),
									Field.secret("token", "Access token", true, "GitHub: fine-grained token with "
											+ "Contents and Pull requests read & write, Actions read; GitLab: api scope; "
											+ "Bitbucket: repository access token; Azure DevOps: PAT with Code read & write"),
									Field.url("apiUrl", "API URL", false, "Only for GitHub Enterprise or self-managed "
											+ "GitLab, e.g. https://ghe.example.com/api/v3"),
									Field.text("organization", "Organization", false, "Azure DevOps organization, to "
											+ "test the token", null))))),
			new Definition(MODELS, "AI model", "The model provider agents use for every role (roles can still be "
					+ "tuned in configuration).", true, true, List.of(
							Field.select("provider", "Provider", List.of("anthropic", "openai", "azure-openai",
									"bedrock", "google-genai", "ollama"), "anthropic", null),
							Field.text("model", "Model", true, "e.g. claude-opus-5-5, gpt-5, llama3.3", "claude-opus-5-5"),
							Field.secret("apiKey", "API key", false, "Not needed for Ollama or for Bedrock with "
									+ "AWS credentials from the environment"),
							Field.url("baseUrl", "Base URL", false, "Ollama, Azure OpenAI endpoint or a gateway"),
							Field.text("region", "AWS region", false, "Bedrock only", null),
							Field.text("deployment", "Deployment", false, "Azure OpenAI only", null))),
			new Definition(JIRA, "Jira", "Start runs from Jira issues labelled for the agent, and post progress "
					+ "back as comments.", false, true, List.of(
							Field.select("deployment", "Deployment", List.of("cloud", "data-center"), "cloud", null),
							Field.url("baseUrl", "Jira URL", true, "e.g. https://acme.atlassian.net"),
							Field.text("email", "Account email", false, "Jira Cloud only; leave empty for a Data "
									+ "Center personal access token", null),
							Field.secret("apiToken", "API token", true, "Cloud: API token; Data Center: personal access "
									+ "token"),
							Field.secret("webhookSecret", "Webhook secret", true, "The secret of the Jira webhook "
									+ "pointing at /api/v1/webhooks/jira"),
							Field.text("triggerLabel", "Trigger label", true, "Adding this label starts a run", "agentic"),
							Field.list("projects", "Projects", true, "Which repository each Jira project's issues "
									+ "change", "key", List.of(
											Field.text("key", "Project key", true, null, null),
											Field.select("kind", "Provider", SCM_KINDS, "GITHUB", null),
											Field.url("cloneUrl", "Repository clone URL", true, null),
											Field.text("baseBranch", "Base branch", false, "Default branch if empty",
													null))))),
			new Definition(SLACK, "Slack", "Start runs with the /agentic slash command and follow them in a thread.",
					false, true, List.of(
							Field.secret("botToken", "Bot token", true, "xoxb-…, with chat:write and commands scopes"),
							Field.secret("signingSecret", "Signing secret", true, "From the Slack app's Basic "
									+ "Information page; requests to /api/v1/webhooks/slack/commands are verified with it"),
							Field.select("defaultKind", "Default repository provider", SCM_KINDS, "GITHUB", null),
							Field.url("defaultRepository", "Default repository", false, "Used when the command names "
									+ "no repository, e.g. https://github.com/acme/shop.git"))),
			new Definition(WEBHOOKS, "Pull request feedback", "Let reviewers ask for changes by commenting on the "
					+ "pull request, and fix failed CI automatically.", false, false, List.of(
							Field.text("mention", "Mention", true, "How reviewers address the bot", "@agentic-sdlc"),
							Field.secret("githubSecret", "GitHub webhook secret", false, "For /api/v1/webhooks/github "
									+ "(issue comments, reviews, workflow runs)"),
							Field.secret("gitlabToken", "GitLab webhook token", false, "For /api/v1/webhooks/gitlab "
									+ "(comments, pipelines)"))));

	private ConnectorCatalog() {
	}

	public static Optional<Definition> find(String id) {
		return ALL.stream().filter(d -> d.id().equals(id)).findFirst();
	}
}
