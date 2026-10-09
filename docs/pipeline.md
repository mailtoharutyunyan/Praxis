# The agent pipeline

What each stage does, how models and the agent loop are configured, how changes are published and revised, and the memory, scanning and evaluation features around them.

[← Back to the README](../README.md)

## What each stage does
| Stage | Who | Output |
|---|---|---|
| Triage | Triage model, request text only. Fails safe to HIGH when unsure. | Risk level and rationale. Untrusted sources always add SPEC. |
| Context | Host plus sandbox: clone, detect toolchain, set up, baseline build | Toolchain, base commit, baseline result |
| Spec | Planner with read-only tools, then a fresh-context critic (`agentic.agent.spec-critic`, on by default) | `spec` artifact: EARS requirements, design, tasks, test plan. The critic checks it against the request and the code, looking for ambiguity, contradictions, gaps, untestable criteria and wrong assumptions. If it asks for changes, the planner revises the spec once. Its findings are shown at the SPEC gate (`spec-review` artifact). |
| Implement: tests first | Test writer (coder model). It can create and edit only test files and has no shell. | Runs on a run's first round (`agentic.agent.tests-first`, on by default). Tests for the acceptance criteria are written, and the app runs them to confirm they fail on the unchanged code, with one retry if they pass. The `tests` artifact records the files and their fingerprints. |
| Implement | Coder with sandbox tools | Changes in the working copy that make the first-written tests pass. Approver feedback, failed checks, review findings and revision requests are fed back in. |
| Verify | Deterministic build and test in the sandbox, then security scans of the changed files | Passes on to review (`diff` and `scan` artifacts), or sends the run back to implement. A committed secret also sends it back. |
| Review | Fresh-context reviewer with read-only tools | `review` artifact. `VERDICT: APPROVE` moves on to the PUBLISH gate; otherwise back to implement. The reviewer is told if any first-written test changed afterwards, and is shown the original version to check that it wasn't weakened. |

Gates show the latest artifacts (`GET /api/v1/runs/{id}/events`, `ARTIFACT_PRODUCED`). Text from tickets and issues is passed to models as data, wrapped in an escaped `<task>` block, with an explicit instruction to ignore embedded commands. Status comments on tickets never quote failure reasons, which can contain model output; they link to the run instead. A janitor removes the sandboxes and working copies of finished runs.


## Models and the agent loop
Each role (`triage`, `planner`, `coder`, `reviewer`) is served by a configurable provider and model (`agentic.models.*`). Supported provider types: `anthropic`, `openai`, `azure-openai`, `ollama`, `bedrock`, `google-genai`. Every role defaults to **Claude Opus 5.5** (`claude-opus-5-5`), reading the key from `ANTHROPIC_API_KEY`. The app starts without any key; a run escalates to a human if a model it needs is not configured.
```yaml
agentic:
  models:
    providers:
      local: { type: ollama, base-url: http://localhost:11434 }
    roles:
      triage: { provider: local, model: qwen3:8b, max-output-tokens: 2000 }
```
The agent loop (`AgentLoop`) is provider-neutral.

Tools the agent can use:
- **Coder:** `list_files`, `view_file`, `search`, `edit_file` (exact, unique-match replace), `create_file`, `run_command`, `show_diff`.
- **Reviewer:** read-only tools only.

Every tool runs inside the sandbox, and none can push, comment or message. Every turn and tool call is a run event.

Limits:
- turns per stage (`agentic.agent.max-turns`);
- the run's token and cost budget, priced from `agentic.models.pricing`;
- stuck detection (the same call repeated);
- old tool outputs cleared from context;
- model calls time out after `agentic.agent.model-timeout` (10 min). Rate limits, overload, 5xx errors, timeouts and I/O errors are retried `model-retries` times (4) with jittered backoff, on top of each SDK's own retries. Usage spent before a stage fails still counts against the budget.

The reviewer's verdict is read only from the last line of its reply, and if triage names several risk levels the highest one wins. Text from outside the operator is wrapped in tagged blocks (`<task>`, `<spec>`, `<guidance>`); any copy of the block's own tag inside the text is escaped, so it cannot close the block early.

`LiveAnthropicSmokeTest` exercises a real tool-call round trip when `ANTHROPIC_API_KEY` is set.


## Publishing and pull requests
Only after a human approves the PUBLISH gate does the PUBLISHING stage:
1. commit the working copy to `agent/<run-id>` (author set by `agentic.scm.author-name` / `author-email`);
2. push it from the host;
3. open a pull request (a merge request on GitLab) that carries the task, spec and automated review.

Both steps are idempotent: a retry finds the existing branch and pull request. The run then waits in `PR_OPEN`. A watcher polls the provider (`agentic.scm.pull-request-poll-interval`): a merged PR completes the run (`DONE`), and one closed without merging cancels it. Merging is always done by people in the provider's UI.

| Provider | Clone URL form | Token (`agentic.scm.tokens."[host]"`) |
|---|---|---|
| GitHub / GitHub Enterprise | `https://github.com/{owner}/{repo}.git` | Fine-grained PAT or GitHub App token. Needs contents write and pull requests write. |
| GitLab / self-managed | `https://gitlab.com/{group}/{sub}/{project}.git` | Project or personal access token with the `api` scope |
| Bitbucket Cloud | `https://bitbucket.org/{workspace}/{repo}.git` | Repository, project or workspace access token. App passwords were removed in 2026. |
| Azure DevOps (incl. `*.visualstudio.com`) | `https://dev.azure.com/{org}/{project}/_git/{repo}` | Organization-scoped PAT with Code (read & write) |

For GitHub Enterprise or self-managed GitLab, set `agentic.scm.api-urls."[host]"`. Setting `agentic.scm.draft-pull-requests=true` opens drafts.


### Revising an open pull request
While a pull request is open, a run can be sent back for changes. The agent works on the same branch, and the run goes through verification, review and the PUBLISH gate again before the new commit is pushed. Revision requests can come from:
- **Review comments.** A comment on the pull request that mentions `@agentic-sdlc` (`agentic.scm.feedback.mention`), for example: `@agentic-sdlc use a constant for the page size`.
  - Line comments carry their file and line.
  - Only people who can push to the repository are heard.
  - The bot replies on the pull request with a link to the run.
- **Failed CI.** When a pipeline fails on the commit the agent pushed last, the agent receives the failed jobs' names and the ends of their logs, and fixes the code. This is limited to `max-ci-fixes` (3) per run.
- **API, MCP and UI.** `POST /api/v1/runs/{id}/revisions` (operator), the `request_revision` MCP tool, or the "Request changes" panel on the run page.

Repeated deliveries are ignored, and a run allows `agentic.scm.feedback.max-revisions` (5) rounds in total. The request text is passed to the agents as data, never as instructions. If a run's workspace is gone, for example after a node failure, the checkout continues from the pushed branch.

| Host | Webhook URL | Events | Secret |
|---|---|---|---|
| GitHub | `/api/v1/webhooks/github` | Issue comments, Pull request review comments, Pull request reviews, Workflow runs | `agentic.scm.feedback.github-secret` (`X-Hub-Signature-256`) |
| GitLab | `/api/v1/webhooks/gitlab` | Comments, Pipeline events | `agentic.scm.feedback.gitlab-token` (`X-Gitlab-Token`) |
| Bitbucket Cloud | `/api/v1/webhooks/bitbucket` | Pull request: Comment created; Repository: Build status created, Build status updated | `agentic.scm.feedback.bitbucket-secret` (the webhook's secret, `X-Hub-Signature`) |
| Azure DevOps | `/api/v1/webhooks/azure-devops` | Two Web Hooks service hooks: Pull request commented on, Build completed | `agentic.scm.feedback.azure-devops-secret` (the service hook's basic authentication password; any user name) |

The token needs read access to CI (GitHub: Actions read; GitLab: `api` scope), and on GitHub it also needs to read collaborator permissions. Some notes on the other hosts:
- **Bitbucket Cloud.**
  - Checking who can push lists the repository's user permissions, which needs admin permission on the repository. Use a workspace access token with the Repositories: Admin and Pipelines: Read scopes.
  - Bitbucket Pipelines failures come with the failed steps' logs. A build from another CI server only gives its name, link and description.
- **Azure DevOps.**
  - The PAT also needs Build (read), Identity (read) and Security (manage) scopes. They are used to read build logs and to check who can contribute to the repository.
  - Use HTTPS: basic authentication sends the password with every request.
  - Line comments do not carry their file and line, because the event does not include them.
  - Only builds of Azure Repos repositories are used.


### Repository memory
Agents remember what they learn about a repository, much like Copilot Memory. The planner, coder and reviewer have a `remember` tool for durable facts such as "integration tests need `-Pit`" or "controllers live in `web/`".
- **Citations.** Every fact must cite a line of code. The citation is checked when the fact is saved.
- **Activation.** A new fact is a candidate. It becomes active only when a human merges the pull request of the run that learned it, and it is discarded if that pull request is closed. This keeps untrusted tasks from planting instructions.
- **Recall.** Before each agent stage, active facts for the repository are checked against the current working copy, and a fact is used only if its cited lines still exist. Used facts stay active for another `agentic.memory.retention` (28 days); unused ones expire.
- **Review.** Facts are listed on the UI's Memory page, through `GET /api/v1/memory` and through the `list_repository_memory` MCP tool. Approvers can activate or disable any fact. Set `agentic.memory.enabled=false` to turn memory off.


### Security scans
After the tests pass, the changed files are scanned. Each scanner runs in its own container with the working copy mounted read-only, no capabilities, and the sandbox's non-root user.
- **Secrets (blocking).** [gitleaks](https://github.com/gitleaks/gitleaks) runs offline and redacts secret values. A secret in a changed file sends the run back to remove it; the same finding in a file the run didn't touch is not reported.
- **Vulnerable dependencies (advisory).** When the change touches a manifest or lockfile, [OSV-Scanner](https://github.com/google/osv-scanner) reports known vulnerabilities by CVSS severity. It needs network access: use the egress proxy, which allows `api.osv.dev` in `dev/egress`. Without network the scan is skipped and the skip is noted.

Findings go to the reviewer, to the "Security" tab at the gates and to the pull request description. Settings live under `agentic.scan.*`: `secrets`, `dependencies`, the pinned images, and `timeout`.


## Evaluation
`evals/` explains how to turn merged fixes into a suite. Each case is replayed through the production pipeline with gates auto-approved and nothing pushed, then graded in its sandbox with hidden fail-to-pass and pass-to-pass checks. The report gives pass@1, pass^k, cost and duration, and `--agentic.eval.min-pass-rate` makes it a CI gate for prompt and model changes.
