# Agentic SDLC

Turns a task (a prompt, a Jira ticket, or another source) into a reviewed pull request. An agent plans the work, implements it in an isolated Docker sandbox, runs the build and tests, and reviews its own changes. Humans approve at gates whose number scales with the task's risk. A human always approves the push, and merging is never automated.

> Status: **M6 (Jira intake and status comments)**. See [the roadmap](#roadmap).

## Stack
- Java 25 (LTS), Spring Boot 4.1.1, Spring WebFlux, Project Reactor
- Spring AI 2.0.1. Providers: Anthropic Claude, OpenAI, Bedrock / Azure / Vertex, Ollama
- PostgreSQL 18 over R2DBC; schema managed by Flyway
- Docker sandbox, one container per run
- SCM providers: GitHub, GitLab, Bitbucket, Azure DevOps

Architecture decisions are recorded in [`docs/adr/`](docs/adr).

## Layout
```
core/   domain model, run state machine and ports. Plain Java + Reactor; no Spring
app/    Spring Boot application: HTTP API, persistence, agent, sandbox and SCM adapters
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
- runs as a non-root user;
- memory, CPU and process limits;
- no credentials in the environment;
- every command is killed by `timeout` when it runs too long.

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

Sandbox settings (`agentic.sandbox.*`): `network` (default `bridge`, or `none` for full isolation), `memory`, `cpus`, `command-timeout`.

> Before running untrusted (ticket-sourced) tasks in production, restrict sandbox egress to package registries with a proxy or network policy; see ADR-0003.

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
- old tool outputs cleared from context.

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

Gates show the latest artifacts (`GET /api/v1/runs/{id}/events`, `ARTIFACT_PRODUCED`). Text from tickets and issues is passed to models as data, wrapped in `<task>`, with an explicit instruction to ignore embedded commands. A janitor removes the sandboxes and working copies of finished runs.

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

## Jira
Label an issue `agentic` (or whatever `agentic.jira.trigger-label` is) to start a run. A run starts when the issue is created with the label, or when the label is added later. `POST /api/v1/webhooks/jira` accepts two senders:
- **Jira admin webhook** (events: issue created and issue updated) with a secret. Requests are verified with `X-Hub-Signature: sha256=…` over the raw body. Retries reuse `X-Atlassian-Webhook-Identifier` and map to the same run.
- **Jira Automation "Send web request"** with header `X-Agentic-Webhook-Token: <agentic.jira.automation-token>` and body `{"key": "{{issue.key}}"}`.

The webhook body only names the issue. The summary, description (rich text converted to plain text) and labels are read back from Jira REST v3, and the project key selects the repository (`agentic.jira.projects.<KEY>`). Ticket text is untrusted: it always passes the SPEC gate and is framed as data for the models.

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
    base-url: https://acme.atlassian.net
    email: bot@acme.com            # Cloud: Basic email:api-token; leave blank for a Data Center PAT
    api-token: ${JIRA_API_TOKEN}
    webhook-secret: ${JIRA_WEBHOOK_SECRET}
    run-link-base: https://agentic.example.com/runs/
    projects:
      SHOP: { kind: GITHUB, clone-url: https://github.com/acme/shop.git, base-branch: main }
```

## API (v1)
All endpoints need a bearer JWT from your OIDC provider (`spring.security.oauth2.resourceserver.jwt.issuer-uri`). Roles are read from the `roles` claim, configurable with `agentic.security.roles-claim` (Keycloak: `realm_access.roles`). Errors are RFC 9457 problem details.

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/v1/tasks` | operator | Submit a task. An optional `Idempotency-Key` header makes retries return the original run. |
| `GET /api/v1/runs?state=&createdBefore=&limit=` | viewer | List runs, newest first. |
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
| M7 | Evaluation harness built from historical tickets |
| M8 | Web UI |
