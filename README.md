# Agentic SDLC

Turns a task (a prompt, a Jira ticket, or another source) into a reviewed pull request. An agent plans the work, implements it in an isolated Docker sandbox, runs the build and tests, and reviews its own changes. Humans approve at gates whose number scales with the task's risk. A human always approves the push, and merging is never automated.

> Status: **M0–M8 complete**: intake, agent pipeline, sandbox, publishing, Jira, evaluation and web UI. See [the roadmap](#roadmap).

## Stack
- Java 25 (LTS), Spring Boot 4.1.1, Spring WebFlux, Project Reactor
- Spring AI 2.0.1. Providers: Anthropic Claude, OpenAI, Bedrock / Azure / Vertex, Ollama
- PostgreSQL 18 over R2DBC; schema managed by Flyway
- Docker sandbox, one container per run
- SCM providers: GitHub, GitLab, Bitbucket, Azure DevOps

Architecture decisions are recorded in [`docs/adr/`](docs/adr).

## Layout
```
core/   domain model, run state machine, agent loop, stages and ports. Plain Java + Reactor; no Spring
app/    Spring Boot application: HTTP API, persistence, LLM, sandbox, git, SCM and Jira adapters
ui/     web UI (React + TypeScript + Vite), packaged into the app jar with -Pui
evals/  how to build evaluation suites from historical tickets
docs/   architecture decision records
```

## How a run flows
```
RECEIVED → TRIAGING → PREPARING_CONTEXT → SPECIFYING ─┬─► [SPEC gate] ─► IMPLEMENTING ⇄ VERIFYING
                                                      └──────────────────►┘                 │
                          ┌─────────────────────────────────────────────────────────────────┘
                          ▼
            [IMPLEMENTATION gate] → REVIEWING → [PUBLISH gate] → PUBLISHING → PR_OPEN → DONE (human merges)
```
| Risk (from triage) | Gates |
|---|---|
| LOW | PUBLISH |
| MEDIUM | SPEC, PUBLISH |
| HIGH (architectural / cross-cutting) | SPEC, IMPLEMENTATION, PUBLISH |

Task text from an untrusted source, such as a Jira ticket or an issue, always adds the SPEC gate. Any live run can move to `NEEDS_HUMAN`, `FAILED` or `CANCELLED`.

## Prerequisites
- JDK 25. If it isn't your default JDK: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`
- A running Docker engine (OrbStack or Docker Desktop), used for Postgres, Testcontainers and the sandbox

Maven resolves from Maven Central through the project's own [`.mvn/settings.xml`](.mvn/settings.xml), so your personal `~/.m2/settings.xml` mirrors don't affect this build.

## Build and run
```bash
./mvnw verify                      # unit + Testcontainers integration tests, ArchUnit, coverage gate
./mvnw install -DskipTests         # once, and after changing core/
./mvnw -pl app spring-boot:run     # starts Postgres from compose.yaml automatically (left running between restarts)
curl localhost:8080/actuator/health
```

### Local run with the placeholder pipeline
The `local` profile enables stub stages, which walk a run through every stage and gate without doing real work. It also accepts tokens signed with a developer key.
```bash
./mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=local
TOKEN=$(scripts/dev-token.sh alice viewer,operator,approver)   # creates .dev/ keys on first use

# Submit a task. A [low], [medium] or [high] tag in the title sets the stub triage risk.
curl -s -X POST localhost:8080/api/v1/tasks -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"[medium] Add /ping","description":"Return pong, with a test",
       "repository":{"kind":"GITHUB","cloneUrl":"https://github.com/acme/shop.git"}}'

# Follow it live; reconnect with Last-Event-ID to resume.
curl -N -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' localhost:8080/api/v1/runs/<id>/events

# Approve the gate it waits at.
curl -s -X POST localhost:8080/api/v1/runs/<id>/decisions -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"gate":"SPEC","decision":"APPROVE","comment":"ok"}'
```

## Workspaces and the sandbox
Each run gets a working copy on the host and one Docker container:
- `<workspace-root>/<run>/repo` is the work tree, mounted at `/workspace` in the container.
- `<workspace-root>/<run>/git` is the git metadata. It is never mounted and hooks are disabled, so nothing the agent writes can run on the host, where the SCM credentials live.

Container hardening:
- all capabilities dropped and `no-new-privileges`;
- runs as a non-root user (the app refuses to start a sandbox as root);
- memory, CPU and process limits;
- no network by default, never the host's network;
- no credentials in the environment;
- every command is killed by `timeout` when it runs too long.

The coder cannot change how its work is checked. `.agentic-sdlc.yml`, `AGENTS.md`/`CLAUDE.md` and the build-file detection are read from the base commit, not from the working tree. Publishing pushes only the diff that was approved at the PUBLISH gate, checked by SHA-256.

The toolchain is detected from root files: Maven, Gradle, npm/pnpm/yarn, Go, Python and .NET. A repository can override it, or declare a custom one, in `.agentic-sdlc.yml`:
```yaml
image: maven:3.9-eclipse-temurin-21
setup: ./mvnw -B -ntp dependency:go-offline   # optional
build: ./mvnw -B -ntp -DskipTests test-compile
test:  ./mvnw -B -ntp verify
```
SCM settings (`agentic.scm.*`):
- `allowed-hosts`: the hosts tasks may point at (SSRF guard).
- `tokens."[host]"`: per-host access tokens from the environment.
- `mirrors`: URL rewrites, like git's `insteadOf`.
- `clone-depth`: default 1.

Sandbox settings (`agentic.sandbox.*`): `network`, `egress-proxy`, `no-proxy`, `user`, `memory`, `cpus`, `command-timeout`.

**Egress.** `network: none` (the default) blocks all network access, so builds must not download anything. To allow dependency downloads without opening the network, use the allowlisting proxy in `dev/egress`. It is a Squid proxy on an internal Docker network whose only way out is that proxy.
```bash
docker compose -p agentic-egress -f dev/egress/compose.yaml up -d
```
```yaml
agentic.sandbox:
  network: agentic-sandbox
  egress-proxy: http://egress:3128   # exported as HTTP(S)_PROXY, JVM proxy properties and Maven settings
```
Package registries (Maven Central, Gradle, npm, PyPI, Go, NuGet) are reachable through the proxy. Cloud metadata, internal hosts and everything else are refused. Edit `dev/egress/allowed-domains.txt` to change the list. The `local` profile uses `bridge` for convenience; never use it for ticket-sourced tasks.

## Deployment
The `Dockerfile` builds one image with the API and the web UI. It runs as uid 10001 with the `prod` profile.
```bash
docker build -t agentic-sdlc .
```
What the container needs:
- **Postgres:** `SPRING_R2DBC_URL` (plus username and password) and `SPRING_FLYWAY_URL` (plus user and password).
- **An OIDC issuer and an audience:**
  - `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`.
  - `AGENTIC_JWT_AUDIENCE`, default `agentic-sdlc`. Access tokens must carry this value in `aud`; in Keycloak, add an audience mapper.
- **Docker access for sandboxes:** `DOCKER_HOST`, or the Docker socket mounted with `--group-add <docker gid>`.
  - Access to the Docker API is root on that host. Use a dedicated sandbox host, or rootless Docker.
  - Containers are bind-mounted from the Docker host, so mount the workspace volume at the same path on the host and in the container. The default path is `/var/lib/agentic/workspaces`, and it must be owned by uid 10001.
- **Model and SCM credentials** as environment variables, such as `ANTHROPIC_API_KEY` and `agentic.scm.tokens`.

Several instances can share one database:
- Each run's working copy stays on the node that holds it (`agentic.worker.node-id`, default: the host name). Keep the node id stable across restarts, for example with a StatefulSet.
- If a node stops sending heartbeats for `node-timeout` (90 s), its runs move to other nodes. Their working copies are then lost: publishing refuses a diff that no longer matches the approved one, and the run goes to a human.
- Jira comments and pull request polling hold a cluster-wide lease, so only one instance runs each.
- On shutdown, a worker stops claiming runs and gives in-flight steps `drain-timeout` (20 s) to finish. Steps still running after that release their lease, so another instance picks them up immediately.

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

## What each stage does
| Stage | Who | Output |
|---|---|---|
| Triage | Triage model, request text only. Fails safe to HIGH when unsure. | Risk level and rationale. Untrusted sources always add SPEC. |
| Context | Host plus sandbox: clone, detect toolchain, set up, baseline build | Toolchain, base commit, baseline result |
| Spec | Planner with read-only tools | `spec` artifact: EARS requirements, design, tasks, test plan |
| Implement | Coder with sandbox tools | Changes in the working copy. Any approver feedback, failed checks or review findings are fed back in. |
| Verify | Deterministic build and test in the sandbox | Passes on to review (`diff` artifact), or sends the run back to implement |
| Review | Fresh-context reviewer with read-only tools | `review` artifact. `VERDICT: APPROVE` moves on to the PUBLISH gate; otherwise back to implement. |

Gates show the latest artifacts (`GET /api/v1/runs/{id}/events`, `ARTIFACT_PRODUCED`). Text from tickets and issues is passed to models as data, wrapped in an escaped `<task>` block, with an explicit instruction to ignore embedded commands. Status comments on tickets never quote failure reasons, which can contain model output; they link to the run instead. A janitor removes the sandboxes and working copies of finished runs.

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

The token needs read access to CI (GitHub: Actions read; GitLab: `api` scope), and on GitHub it also needs to read collaborator permissions. On Bitbucket and Azure DevOps, use the API, MCP or UI to request revisions; replies are still posted on the pull request.

## Jira
Label an issue `agentic` (or whatever `agentic.jira.trigger-label` is) to start a run. A run starts when the issue is created with the label, or when the label is added later. `POST /api/v1/webhooks/jira` accepts two senders:
- **Jira admin webhook** (events: issue created and issue updated) with a secret. Requests are verified with `X-Hub-Signature: sha256=…` over the raw body. Retries reuse `X-Atlassian-Webhook-Identifier` (Cloud) or the body `timestamp` (Data Center) and map to the same run.
- **Jira Automation "Send web request"** with header `X-Agentic-Webhook-Token: <agentic.jira.automation-token>` and body `{"key": "{{issue.key}}"}`.

The webhook body only names the issue. The summary, description (rich text converted to plain text) and labels are read back from Jira (REST v3 on Cloud, REST v2 on Data Center), and the project key selects the repository (`agentic.jira.projects.<KEY>`). Ticket text is untrusted: it always passes the SPEC gate and is framed as data for the models.

The run's progress is posted back as issue comments, once each, through a durable cursor:
- run started;
- waiting at a gate;
- pull request opened;
- needs a human (with the reason);
- failed, cancelled, or done.

```yaml
agentic:
  jira:
    enabled: true
    deployment: cloud              # or data-center (REST v2, plain-text comments)
    base-url: https://acme.atlassian.net
    email: bot@acme.com            # Cloud: Basic email:api-token; leave blank for a Data Center PAT
    api-token: ${JIRA_API_TOKEN}
    webhook-secret: ${JIRA_WEBHOOK_SECRET}
    run-link-base: https://agentic.example.com/#/runs/
    projects:
      SHOP: { kind: GITHUB, clone-url: https://github.com/acme/shop.git, base-branch: main }
```

### Local Jira Data Center
`dev/jira/compose.yaml` runs Jira Software Data Center with its own Postgres on http://localhost:8090:

1. `docker compose -p agentic-jira -f dev/jira/compose.yaml up -d`, then open the setup wizard and paste a [timebomb license](https://developer.atlassian.com/platform/marketplace/timebomb-licenses-for-testing-server-apps/).
2. Create a project and a personal access token (profile → Personal Access Tokens). Basic auth is disabled in Jira 11, so use the token with an empty `email`.
3. Register the webhook. On Data Center the secret goes in `configuration.SECRET`; a top-level `secret` field is silently ignored, and the requests then arrive unsigned:
   ```bash
   curl -X POST -H "Authorization: Bearer $JIRA_PAT" -H 'Content-Type: application/json' \
     http://localhost:8090/rest/jira-webhook/1.0/webhooks -d '{"name":"agentic-sdlc",
     "url":"http://host.docker.internal:8080/api/v1/webhooks/jira","active":true,
     "events":["jira:issue_created","jira:issue_updated"],
     "configuration":{"EXCLUDE_BODY":"false","SECRET":"'"$JIRA_WEBHOOK_SECRET"'"}}'
   ```
4. Start the app with `--agentic.jira.deployment=data-center --agentic.jira.base-url=http://localhost:8090` and the token and secret in `AGENTIC_JIRA_APITOKEN` and `AGENTIC_JIRA_WEBHOOKSECRET`, then add the `agentic` label to an issue.

## Web UI
The UI is a React app served by the backend at `/`:
- **Runs list:** filtered to "Needs me", in progress, PR open and finished.
- **New task** form.
- **Run page:** pipeline progress, a live activity feed (SSE with resume), cost, and an approval panel showing the spec, diff and review. Approve, request changes (with a comment the agent receives), or reject. Raise risk, cancel and resume are also there.

What you see depends on your role. Links to a run look like `/#/runs/<id>`, so set `agentic.jira.run-link-base` to `https://<host>/#/runs/`.

Sign-in:
- **Production:** OIDC authorization code with PKCE (`agentic.ui.auth-mode=oidc`, plus `issuer` and `client-id` for a public client).
- **Local profile:** paste a token from `scripts/dev-token.sh`.

Security: the backend sends a strict Content-Security-Policy, and model output is rendered as sanitized Markdown.
```bash
./mvnw -Pui package -DskipTests            # jar with the UI (needs Node 24+)
cd ui && npm ci && npm run dev             # hot reload on :5173, proxied to the backend on :8080
cd ui && npm test                          # UI unit tests
```

## MCP: use it from any AI client
`/mcp` is an MCP server (Streamable HTTP, stateless; ADR-0005). Claude, ChatGPT, Cursor and IDE agents can use it to hand over work and follow it.

| Tool | Role | What it does |
|---|---|---|
| `submit_task` | operator | Start a run (title, description, clone URL, host kind, optional base branch and idempotency key). |
| `list_runs` | viewer | Runs, newest first, optionally filtered by state. |
| `get_run` | viewer | State, risk, pending gate, usage, a link to the UI, and what happens next. |
| `get_run_artifact` | viewer | The latest `spec`, `diff`, `review` or `pull-request`. |
| `get_run_events` | viewer | A page of the event log; pass `afterSeq` to read only what is new. |
| `cancel_run`, `resume_run` | operator | Stop a run, or continue one that is waiting for a human. |
| `request_revision` | operator | Ask for changes to a run's open pull request. |

Gate approvals are not available over MCP. A human approves specs, implementations and pushes in the UI. Tasks submitted over MCP are untrusted, because an assistant may relay text it read elsewhere, so they always stop at the SPEC gate.

**Authentication.** Clients send the user's access token, which needs the same roles as the API.
- Clients that support MCP OAuth find the identity provider on their own. A 401 points to `/.well-known/oauth-protected-resource`, which names the issuer.
- Tokens must carry this API's audience. If your IdP sets `aud` to the resource URL (RFC 8707), add `https://<host>/mcp` to `AGENTIC_JWT_AUDIENCE`, comma-separated.
- Set `agentic.mcp.resource` to the public `/mcp` URL when behind a proxy, and `agentic.mcp.run-link-base` (`https://<host>/#/runs/`) for links.

```bash
# Claude Code
claude mcp add --transport http agentic-sdlc https://agentic.example.com/mcp --header "Authorization: Bearer $TOKEN"
```
```json
// Cursor (.cursor/mcp.json) and other clients that take a URL plus headers
{ "mcpServers": { "agentic-sdlc": { "url": "https://agentic.example.com/mcp",
    "headers": { "Authorization": "Bearer ${TOKEN}" } } } }
```
Claude Desktop and claude.ai: add a custom connector with the `/mcp` URL and sign in through your IdP. Locally, use `scripts/dev-token.sh` for a token, and inspect the server with `npx @modelcontextprotocol/inspector@2.8.0`.

## Evaluation
`evals/` explains how to turn merged fixes into a suite. Each case is replayed through the production pipeline with gates auto-approved and nothing pushed, then graded in its sandbox with hidden fail-to-pass and pass-to-pass checks. The report gives pass@1, pass^k, cost and duration, and `--agentic.eval.min-pass-rate` makes it a CI gate for prompt and model changes.

## API (v1)
All endpoints need a bearer JWT from your OIDC provider (`spring.security.oauth2.resourceserver.jwt.issuer-uri`). Roles are read from the `roles` claim, configurable with `agentic.security.roles-claim` (Keycloak: `realm_access.roles`). Errors are RFC 9457 problem details.

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/v1/tasks` | operator | Submit a task. An optional `Idempotency-Key` header makes retries return the original run. |
| `POST /api/v1/runs/{id}/revisions` | operator | Ask for changes to the open pull request: `{"text": "...", "location": "src/App.java:42"}`. |
| `GET /api/v1/runs?state=&createdBefore=&beforeId=&limit=` | viewer | List runs, newest first. Pass a page's `nextCreatedBefore` and `nextBeforeId` to get the next page. |
| `GET /api/v1/runs/{id}` | viewer | Run with its task, risk, gates and usage. |
| `GET /api/v1/runs/{id}/events?afterSeq=&limit=` | viewer | Event log page (JSON). |
| `GET /api/v1/runs/{id}/events` (`Accept: text/event-stream`) | viewer | Live SSE stream. `id` is the event sequence, so `Last-Event-ID` resumes. |
| `POST /api/v1/runs/{id}/decisions` | approver | `{gate, decision: APPROVE\|REQUEST_CHANGES\|REJECT, comment}` |
| `POST /api/v1/runs/{id}/risk` | approver | Raise risk, which adds gates and never removes them. |
| `POST /api/v1/runs/{id}/cancel` | operator | Cancel a live run. |
| `POST /api/v1/runs/{id}/resume` | operator | Continue a run escalated to `NEEDS_HUMAN`. |

Operations: `/actuator/health/{liveness,readiness}` and `/actuator/prometheus`, served on management port 8081 in the `prod` profile. Metrics: `agentic.stage.duration`, `agentic.run.transitions`, `agentic.stage.failures`, `agentic.step.abandoned`.

## Roadmap
| Milestone | Scope |
|---|---|
| **M0** ✅ | Multi-module skeleton, schema, CI, ADRs, ArchUnit, coverage gate |
| **M1** ✅ | Task intake API, run engine and leased worker, SSE event stream, approvals, OAuth2 roles |
| **M2** ✅ | Docker sandbox, JGit clone, build/test detection |
| **M3** ✅ | Spring AI model registry (per-role provider/model), tool loop with budgets |
| **M4** ✅ | Triage → context → spec → implement ⇄ verify → review, all gates |
| **M5** ✅ | SCM providers: GitHub, GitLab, Bitbucket, Azure DevOps (branch push + PR) |
| **M6** ✅ | Jira intake (webhook + REST) and status comments |
| **M7** ✅ | Evaluation harness built from historical tickets |
| **M8** ✅ | Web UI |
| **M9** ✅ | MCP server for AI clients |
