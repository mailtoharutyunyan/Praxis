# Agentic SDLC

Turns a task (a prompt, a Jira ticket, or another source) into a reviewed pull request. An agent plans the work, implements it in an isolated Docker sandbox, runs the build and tests, and reviews its own changes. Humans approve at gates whose number scales with the task's risk. A human always approves the push, and merging is never automated.

> Status: **M0–M8 complete**: intake, agent pipeline, sandbox, publishing, Jira, evaluation and web UI. See [the roadmap](#roadmap).

## Quick start: everything with Docker Compose
```bash
docker compose up -d --build
```
Then open http://localhost:8080. This one command builds the app and its web UI and starts them with Postgres, the sandbox egress proxy and a Docker API proxy for sandboxes ([`compose.yaml`](compose.yaml)).

On the first visit the UI walks you through setup:
1. **Admin account.** Create the first user. It has every role, and it is the account that configures connectors.
2. **Required connectors.** Until these are saved, the UI shows only the setup wizard.
   - **Application:** its public URL, used for links to runs.
   - **Code hosts:** GitHub, GitLab, Bitbucket or Azure DevOps, each with an access token. Each host you add is allowed for tasks.
   - **Model:** Anthropic, OpenAI, Azure OpenAI, Bedrock, Gemini or Ollama, used for every agent role. For Ollama on the same machine, use `http://host.docker.internal:11434`.
3. **Optional connectors.** Save or **Skip** each one, and add skipped ones later under **Settings**: Jira, Slack, and pull request feedback (GitHub and GitLab webhooks).

Each step has **Test connection**. Changes apply without a restart. Secrets are write-only in the UI, and they are encrypted (AES-256-GCM) in Postgres with a key from `AGENTIC_SECRETS_KEY` or, if that is unset, a key generated in the `agentic-data` volume. Back the key up together with the database. Set `POSTGRES_PASSWORD` and `AGENTIC_SECRETS_KEY` (`openssl rand -base64 32`) in a `.env` file before the first start; `AGENTIC_PORT` changes the port.

Stop with `docker compose down`. Your data stays in the volumes until you add `-v`.

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
./mvnw -pl app spring-boot:run     # starts Postgres from dev/postgres/compose.yaml automatically (left running)
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
### Microservices: monorepos, mixed toolchains, sidecars
See ADR-0006.
- **Services.** If the repository has no root build, every top-most directory (up to 3 levels deep) with a recognised build file is a service, and each gets a sandbox container from its own toolchain image. Node, Java, Go, Python and .NET can share a repository.
- **Scoped verification.** Only the services a change touches are built and tested. A change outside every service (shared files) verifies all of them. The baseline at context preparation builds every service.
- **Agent commands.** `run_command` takes `service: <name>` to run in that service's toolchain from its directory.
- **Explicit list.** `.agentic-sdlc.yml` can name the services instead of relying on detection. It can also declare **sidecars**, the containers the tests need, on a private per-run network with no internet; their names are the hostnames:
```yaml
services:
  - { name: orders, path: services/orders }                         # toolchain detected from its files
  - { name: web, path: apps/web, image: node:24-bookworm, test: npm test -- --ci }
sidecars:
  - { name: postgres, image: postgres:16-alpine, env: { POSTGRES_PASSWORD: test }, ready: pg_isready -U postgres }
  - { name: kafka, image: apache/kafka:4.1.0 }
env:                                                                 # for every service's build
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/postgres
```
**Changes across repositories.** A task can name **companion repositories**, for example an API's consumers, through `companions` in `POST /api/v1/tasks`, `companionRepositories` in the MCP `submit_task` tool, the UI's new-task dialog, or `agentic.jira.projects.<KEY>.companions`.
- **Layout.** Each companion is checked out at `/workspace/.repos/<alias>/` with its own `agent/<run>` branch. The agent changes all repositories together, and the diff and the PUBLISH approval cover them all.
- **Publishing.** Every repository that changed gets its own pull request. The pull requests reference one another and are cross-linked by a comment.
- **Completion.** The run is DONE when all of them are merged, and CANCELLED once none is open but not all were merged.
- **Feedback.** Review comments and CI failures on any of the pull requests send the run back for a revision.

**API contracts.** A change to an OpenAPI, AsyncAPI, protobuf, GraphQL, Avro or WSDL file is flagged to the reviewer, at the gates ("Checks") and in the pull request, with a reminder to check consumers.

Sidecars don't see the code and get no credentials. Testcontainers can't run inside the sandbox, because that would need the Docker socket; declare the same containers as sidecars and point the tests at them through `env`.

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
- **Sign-in:** `AGENTIC_SECURITY_MODE=local` for built-in accounts (see [Web UI](#web-ui)), or an OIDC issuer and an audience:
  - `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`.
  - `AGENTIC_JWT_AUDIENCE`, default `agentic-sdlc`. Access tokens must carry this value in `aud`; in Keycloak, add an audience mapper.
- **Docker access for sandboxes:** `DOCKER_HOST`, or the Docker socket mounted with `--group-add <docker gid>`.
  - Access to the Docker API is root on that host. Use a dedicated sandbox host, or rootless Docker.
  - Sandboxes must see the run's files. Either mount a Docker volume at the workspace root and name it in `AGENTIC_SANDBOX_WORKSPACEVOLUME`, so sandboxes mount their run's directory from it as a volume subpath (Docker Engine 26+; this is what `compose.yaml` does), or bind-mount a host directory at the same path on the host and in the container. The default path is `/var/lib/agentic/workspaces`, and it must be owned by uid 10001.
- **Model and SCM credentials** as environment variables, such as `ANTHROPIC_API_KEY` and `agentic.scm.tokens`.

Several instances can share one database:
- Each run's working copy stays on the node that holds it (`agentic.worker.node-id`, default: the host name). Keep the node id stable across restarts, for example with a StatefulSet.
- If a node stops sending heartbeats for `node-timeout` (90 s), its runs move to other nodes. Their working copies are then lost: publishing refuses a diff that no longer matches the approved one, and the run goes to a human.
- Jira comments and pull request polling hold a cluster-wide lease, so only one instance runs each.
- On shutdown, a worker stops claiming runs and gives in-flight steps `drain-timeout` (20 s) to finish. Steps still running after that release their lease, so another instance picks them up immediately.

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
| Spec | Planner with read-only tools, then a fresh-context critic (`agentic.agent.spec-critic`, on by default) | `spec` artifact: EARS requirements, design, tasks, test plan. The critic checks it against the request and the code, looking for ambiguity, contradictions, gaps, untestable criteria and wrong assumptions. If it asks for changes, the planner revises the spec once. Its findings are shown at the SPEC gate (`spec-review` artifact). |
| Implement: tests first | Test writer (coder model). It can create and edit only test files and has no shell. | Runs on a run's first round (`agentic.agent.tests-first`, on by default). Tests for the acceptance criteria are written, and the app runs them to confirm they fail on the unchanged code, with one retry if they pass. The `tests` artifact records the files and their fingerprints. |
| Implement | Coder with sandbox tools | Changes in the working copy that make the first-written tests pass. Approver feedback, failed checks, review findings and revision requests are fed back in. |
| Verify | Deterministic build and test in the sandbox, then security scans of the changed files | Passes on to review (`diff` and `scan` artifacts), or sends the run back to implement. A committed secret also sends it back. |
| Review | Fresh-context reviewer with read-only tools | `review` artifact. `VERDICT: APPROVE` moves on to the PUBLISH gate; otherwise back to implement. The reviewer is told if any first-written test changed afterwards, and is shown the original version to check that it wasn't weakened. |

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

## Slack
Start runs from Slack with a slash command, and follow them in a thread:
```
/agentic https://github.com/acme/shop.git Add a /health endpoint with a test
/agentic Add a /health endpoint with a test        # uses the connector's default repository
```
1. Create a Slack app with the bot scopes `chat:write` and `commands`, and install it in your workspace.
2. Add the slash command `/agentic` with the request URL `https://<public URL>/api/v1/webhooks/slack/commands`. Settings → Slack shows the exact URL.
3. In Settings → Slack, enter the bot token (`xoxb-…`), the signing secret and, optionally, a default repository.
4. Invite the app to the channels where it is used.

Each request is verified with the signing secret (Slack's `v0` HMAC over timestamp and body), and requests older than five minutes are rejected. The app posts the request in the channel and runs the task from that thread. Progress (gates, the pull request, the outcome) is posted as thread replies. Slack text is untrusted, like a ticket, so it always passes the SPEC gate, and the echoed request is escaped so it cannot mention `@channel`.

## Web UI
The UI is a React app served by the backend at `/`:
- **Runs list:** filtered to "Needs me", in progress, PR open and finished.
- **New task** form.
- **Run page:** pipeline progress, a live activity feed (SSE with resume), cost, and an approval panel showing the spec, diff and review. Approve, request changes (with a comment the agent receives), or reject. Raise risk, cancel and resume are also there.

What you see depends on your role. Links to a run look like `/#/runs/<id>`, so set `agentic.jira.run-link-base` to `https://<host>/#/runs/`.

Sign-in:
- **Production with an identity provider:** OIDC authorization code with PKCE (`agentic.ui.auth-mode=oidc`, plus `issuer` and `client-id` for a public client).
- **Built-in** (`AGENTIC_SECURITY_MODE=local`, used by `compose.yaml`): usernames and BCrypt-hashed passwords in Postgres. Five failed attempts lock a username for a minute. The app signs RS256 tokens with a key stored encrypted in the database, so every instance accepts them.
- **Local profile:** paste a token from `scripts/dev-token.sh`.

**Setup and Settings.** Until a code host and a model are configured, signed-in admins see the setup wizard and everyone else a notice. Admins manage connectors under **Settings**. Forms are generated from the connector catalog (`GET /api/v1/connectors`), so a new connector needs no UI change.

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
| `list_repository_memory` | viewer | What agents learned about a repository. |

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
| `GET /api/v1/memory?repository=` | viewer | What agents learned about a repository: facts, citations, status. |
| `POST /api/v1/memory/{id}/status` | approver | `{"status": "ACTIVE"}` or `"DISABLED"`. |
| `POST /api/v1/runs/{id}/revisions` | operator | Ask for changes to the open pull request: `{"text": "...", "location": "src/App.java:42"}`. |
| `GET /api/v1/runs?state=&createdBefore=&beforeId=&limit=` | viewer | List runs, newest first. Pass a page's `nextCreatedBefore` and `nextBeforeId` to get the next page. |
| `GET /api/v1/runs/{id}` | viewer | Run with its task, risk, gates and usage. |
| `GET /api/v1/runs/{id}/events?afterSeq=&limit=` | viewer | Event log page (JSON). |
| `GET /api/v1/runs/{id}/events` (`Accept: text/event-stream`) | viewer | Live SSE stream. `id` is the event sequence, so `Last-Event-ID` resumes. |
| `POST /api/v1/runs/{id}/decisions` | approver | `{gate, decision: APPROVE\|REQUEST_CHANGES\|REJECT, comment}` |
| `POST /api/v1/runs/{id}/risk` | approver | Raise risk, which adds gates and never removes them. |
| `POST /api/v1/runs/{id}/cancel` | operator | Cancel a live run. |
| `POST /api/v1/runs/{id}/resume` | operator | Continue a run escalated to `NEEDS_HUMAN`. |
| `GET /api/v1/setup` | public | Setup status: each step done, skipped or pending; no secrets. |
| `POST /api/v1/setup/admin` | public, once | Create the first admin (built-in sign-in only); returns a token. |
| `POST /api/v1/auth/login` | public | Built-in sign-in: `{username, password}` → `{token, expiresAt, roles}`. |
| `GET /api/v1/connectors` | admin | Connector forms, settings and which secrets are set (never their values). |
| `PUT /api/v1/connectors/{id}` | admin | Save `{config, secrets}`; an empty secret keeps the stored one. |
| `POST /api/v1/connectors/{id}/test` | admin | Check the given settings against the service without saving. |
| `POST /api/v1/connectors/{id}/skip` | admin | Skip an optional connector. |

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
| **M10** ✅ | One-command Docker Compose install, setup wizard, runtime connectors (git, models, Jira, Slack, PR feedback), built-in sign-in |
