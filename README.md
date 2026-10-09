# Agentic SDLC

[![CI](https://github.com/mailtoharutyunyan/Praxis/actions/workflows/ci.yml/badge.svg)](https://github.com/mailtoharutyunyan/Praxis/actions/workflows/ci.yml)
[![CodeQL](https://github.com/mailtoharutyunyan/Praxis/actions/workflows/codeql.yml/badge.svg)](https://github.com/mailtoharutyunyan/Praxis/actions/workflows/codeql.yml)

**Governed AI agents that turn tasks into reviewed pull requests.**

Describe a change in the web UI, from your AI assistant, in Slack or on a Jira issue. Agents plan it, write tests,
implement it in an isolated sandbox, verify it and review it. People approve at checkpoints that scale with the risk
of the change, and the result arrives as a pull request on your code host. Nothing is pushed without a human
approval, and merging always stays with people.

- **Human control by design.** Every change needs approval before it is pushed. Riskier changes also need their plan
  and implementation approved.
- **Isolated execution.** Each run builds and tests in its own locked-down container, with no credentials and only
  allow-listed network access.
- **Bring your own model.** Anthropic, OpenAI, Azure OpenAI, Amazon Bedrock, Google Gemini or a local Ollama model. Or
  run the agents on **Claude Code** with a Claude subscription.
- **Works where you work.** Code hosts: GitHub, GitLab, Bitbucket and Azure DevOps. Intake: Jira and Slack, and Claude
  Code, Codex CLI and Gemini CLI over MCP. A REST API with an OpenAPI description.
- **Fully observable.** Live progress, every agent step and tool call, and cost and tokens per run.

---

## Contents
- [How it works](#how-it-works)
- [Features](#features)
- [Quick start](#quick-start)
- [Using the app](#using-the-app)
- [Configuration](#configuration)
- [Security model](#security-model)
- [Documentation](#documentation)
- [Development](#development)
- [Architecture](#architecture)
- [Roadmap](#roadmap)

## How it works

```
 Task ──► Triage ──► Context ──► Spec ──► [SPEC gate] ──► Implement ⇄ Verify ──► [IMPLEMENTATION gate]
                                                                                        │
     DONE ◄── Merged by a human ◄── Pull request ◄── Publish ◄── [PUBLISH gate] ◄── Review ◄┘
```

1. **Triage** rates the risk of the request as low, medium or high.
2. **Context.** The repository is cloned, its toolchain detected and a baseline build run in the sandbox.
3. **Spec.** A planner writes a specification: requirements, design, tasks and test plan. A separate critic checks it
   against the request and the code, and the planner revises it once if needed.
4. **Implement.** Tests for the change are written first and must fail on the old code. The coder then implements
   until they pass.
5. **Verify.** The build and tests run in the sandbox, followed by secret and dependency scans.
6. **Review.** An independent reviewer with read-only access approves the change or sends it back.
7. **Publish.** After approval the change is pushed to a branch and a pull request is opened. Review comments and failed
   CI on that pull request send the run back for another round.

Which approvals a run needs depends on its risk; gates are only ever added, never removed:

| Risk | Approval gates |
|---|---|
| Low | Publish |
| Medium | Spec, Publish |
| High (architectural or cross-cutting) | Spec, Implementation, Publish |

Text from outside the operator, such as a Jira issue, a Slack message or an AI client, is treated as untrusted and
always stops at the Spec gate. So does any task submitted with **Review the plan before coding**.

## Features

| Area | What you get |
|---|---|
| **Pipeline** | Risk-based gates, spec critic, tests-first implementation, deterministic verification, independent review, revisions from pull request comments and failed CI, per-run token and cost budgets. |
| **Sandbox** | One container per run (or per service), with no capabilities, a non-root user, resource limits and no credentials. Egress only through an allow-listing proxy; optional gVisor runtime. |
| **Repositories** | Monorepos with mixed toolchains (Java, Node, Go, Python, .NET), test sidecars such as Postgres or Kafka, changes spanning several repositories with linked pull requests. |
| **Models** | Any supported provider per agent role, or the Claude Code engine; prompt caching; cost tracking. |
| **Intake** | Web UI, REST API, MCP for AI assistants, Slack slash command, Jira label. |
| **Knowledge** | Repository memory: agents save facts about a codebase, each citing the code that shows it. Facts become active only when a human merges the change. |
| **Security** | Secret scanning (gitleaks) and dependency audit (OSV) of every change, approved-diff fingerprinting, encrypted secrets, API tokens, role-based access, rate limits. |
| **Insights** | Delivery dashboard: runs, outcomes, success rate, time to pull request, cost and tokens over time. |
| **Operations** | One-command Docker Compose install with a setup wizard, health checks, nightly backups, data retention, key rotation, multi-instance support, JSON logs, Prometheus metrics. |

## Quick start

### 1. Requirements
- Docker Engine 26 or later (Docker Desktop, OrbStack or Docker on Linux), with about 8 GB of memory available.
- A GitHub, GitLab, Bitbucket or Azure DevOps repository, and an access token for it.
- Access to a model: a Claude subscription (for the Claude Code engine) or an API key for a supported provider.

### 2. Start the stack
```bash
git clone https://github.com/mailtoharutyunyan/Praxis.git && cd Praxis
docker compose --profile claude-code up -d --build
```
This starts:
- the app with its web UI;
- PostgreSQL;
- the sandbox egress proxy and a Docker API proxy;
- nightly backups;
- with the `claude-code` profile, a one-off job that installs Claude Code for the agents.

Leave the profile out if you only use model API keys. Optional settings go in a `.env` file; see
[Configuration](#configuration).

### 3. Create the admin account
Open **http://localhost:8080**. Creating the first account needs a one-time setup code. The app prints it in its log,
so that only whoever runs the server can claim the install:
```bash
docker compose logs app | grep "setup code"
```

### 4. Connect your tools
The setup wizard opens after sign-in. Each step has **Test connection**.

| Step | Required | What to enter |
|---|---|---|
| This app | Yes | The URL people open the app at (prefilled). |
| Code hosts | Yes | Provider, host and an access token. GitHub: a fine-grained token with **Contents** and **Pull requests** read & write and **Actions** read. |
| AI model | Yes | Either a provider, model and API key, or **Engine: Claude Code** with a token from `claude setup-token`. |
| Jira, Slack, Pull request feedback | No | Skip them now and add them later under **Settings**. |

### 5. Run your first task
1. Go to **Runs → New task**.
2. Enter a title, a description and the repository's clone URL, and click **Create run**.
3. Follow the run on its page and approve when it asks.
4. Merge the pull request on your code host.

Stop the stack with `docker compose down`. Your data stays in Docker volumes until you add `-v`.

## Using the app

### In the web UI
- **Runs** lists every run. **Needs me** shows the runs waiting for your approval; the sidebar shows their count.
- A **run page** shows:
  - the progress bar and current stage;
  - a live activity feed (tick *show tool calls* for every command and file edit);
  - cost and tokens;
  - at a gate: the specification, the spec check, the tests, the changes, the scan results and the review.

  At a gate, **Approve**, **Request changes** with a comment, or **Reject**.
- **Insights** summarises delivery over 7, 30 or 90 days.
- **Memory** lists what agents learned about each repository.
- **Settings** (admins) manages connectors, and **Users** manages accounts.
- **API & AI CLI** creates personal API tokens.

### From an AI assistant (Claude Code, Codex CLI, Gemini CLI)
Create a token under **API & AI CLI**. The page shows the exact command for your CLI, for example:
```bash
claude mcp add --transport http --scope user agentic-sdlc https://agentic.example.com/mcp \
  --header "Authorization: Bearer asdlc_…"
```
Then ask in chat: *"Use agentic-sdlc to add a /health endpoint to acme/shop and tell me when it waits for approval."*
Assistants can submit and follow tasks and request revisions; approvals stay in the web UI.
See [AI clients and the MCP server](docs/ai-clients.md).

### From Slack and Jira
- **Slack:** `/agentic https://github.com/acme/shop.git Add a /health endpoint`. Progress is posted in the thread.
- **Jira:** add the `agentic` label to an issue. Progress is posted as comments.

See [Jira and Slack](docs/integrations.md).

### From scripts and CI
```bash
curl -X POST https://agentic.example.com/api/v1/tasks \
  -H "Authorization: Bearer asdlc_…" -H 'Content-Type: application/json' \
  -d '{"title":"Add /ping","description":"Return pong, with a test",
       "repository":{"kind":"GITHUB","cloneUrl":"https://github.com/acme/shop.git"}}'
```
The OpenAPI description is served at `/v3/api-docs`; see the [API reference](docs/api.md).

## Configuration

### Install settings
Set these in a `.env` file next to `compose.yaml` before the first start.

| Variable | Default | Purpose |
|---|---|---|
| `AGENTIC_PORT` | `8080` | Host port of the UI and API. |
| `POSTGRES_PASSWORD` | `agentic-change-me` | Database password. Change it. |
| `AGENTIC_SECRETS_KEY` | generated | Base64 of 32 random bytes (`openssl rand -base64 32`) that encrypts stored secrets. Back it up with the database. |
| `AGENTIC_SECRETS_KEY_PREVIOUS` | | Old keys during a key rotation; secrets are re-encrypted on startup. |
| `AGENTIC_SETUP_CODE` | generated | A fixed first-run setup code instead of a generated one. |
| `AGENTIC_SANDBOX_RUNTIME` | Docker default | Container runtime for sandboxes, e.g. `runsc` for gVisor. |
| `BACKUP_KEEP_DAYS` | `14` | Days of nightly database dumps to keep. |
| `CLAUDE_CODE_VERSION` | `2.1.282` | Claude Code build installed for the Claude Code engine. |

All application settings live under `agentic.*` in [`application.yaml`](app/src/main/resources/application.yaml). Any
of them can be overridden with an environment variable, for example `AGENTIC_LIMITS_MAXCOSTUSD=20` for the per-run
cost limit.

### Per-repository settings
A repository can tell the sandbox how to build and test it, in `.agentic-sdlc.yml` at its root:
```yaml
services:
  - { name: api, path: services/api }                                   # toolchain detected
  - { name: web, path: apps/web, image: node:24-bookworm, test: npm test }
sidecars:
  - { name: postgres, image: postgres:18, env: { POSTGRES_PASSWORD: test }, ready: pg_isready -U postgres }
```
`AGENTS.md` or `CLAUDE.md` in the repository are passed to the agents as project instructions. See
[Workspaces, sandbox and microservices](docs/sandbox.md).

## Security model
- **Approvals.** Nothing is pushed without a human approval at the Publish gate. Only the exact diff that was approved
  can be published (SHA-256 fingerprint). Merging is never automated.
- **Least privilege.** Agents never hold code-host credentials: the app clones and pushes itself. Each agent role gets
  only the tools it needs, and reviewers are read-only.
- **Untrusted input.** Text from tickets, chat and AI clients is passed to models as data. It always stops at the Spec
  gate and never runs on the Claude Code engine.
- **Isolation.** Sandboxes run without capabilities, as a non-root user, with resource limits. Their only network
  route is an allow-listing proxy that reaches package registries and nothing else.
- **Secrets.**
  - Connector secrets are encrypted with AES-256-GCM and never returned by the API.
  - API tokens are stored as hashes, scoped, expiring and revocable.
  - Every change is scanned for leaked secrets.
- **Access.**
  - Roles: `viewer`, `operator`, `approver`, `admin`.
  - Sign-in: built-in accounts with lockout, or your OIDC provider.
  - Rate limits and a strict Content Security Policy.

The trade-offs and their rationale are recorded in the [architecture decision records](docs/adr).

## Documentation
| Guide | Contents |
|---|---|
| [The agent pipeline](docs/pipeline.md) | Stages, models and the agent loop, publishing and revisions, repository memory, security scans, evaluation. |
| [Workspaces, sandbox and microservices](docs/sandbox.md) | Sandbox hardening, toolchain detection, monorepos, sidecars, multi-repository changes, egress. |
| [Agents on Claude Code](docs/claude-code-engine.md) | Using a Claude subscription instead of an API key: how it works and its trade-offs. |
| [AI clients and the MCP server](docs/ai-clients.md) | API tokens; connecting Claude Code, Codex CLI, Gemini CLI and other MCP clients. |
| [Jira and Slack](docs/integrations.md) | Starting and following runs from Jira issues and Slack. |
| [Web UI, sign-in and accounts](docs/web-ui.md) | The UI, sign-in modes and account management. |
| [Deployment and operations](docs/operations.md) | Running outside Compose, scaling, rate limits, retention, backups, key rotation, supply chain. |
| [REST API reference](docs/api.md) | All endpoints and the roles they need. |
| [Development](docs/development.md) | Building and testing from source, and the placeholder pipeline. |
| [Architecture decisions](docs/adr) | ADR-0001 to ADR-0009. |

## Development
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # JDK 25
./mvnw verify                                      # unit and integration tests (needs Docker), ArchUnit, coverage gates
./mvnw -pl app spring-boot:run                     # run the API locally against a dev database
cd ui && npm ci && npm run dev                     # web UI with hot reload on :5173
```
See [Development](docs/development.md) for the placeholder pipeline and developer tokens.

## Architecture
- **Stack:** Java 25, Spring Boot 4.1 (WebFlux), Spring AI 2.0, PostgreSQL 18 (R2DBC, Flyway), Docker, React and
  TypeScript.
- **Layout:** a hexagonal design, enforced by ArchUnit.
  ```
  core/   domain, run state machine, agent loop and stages, ports. Plain Java and Reactor, no Spring
  app/    Spring Boot application: HTTP API, MCP server, persistence, models, sandbox, git, code hosts, Jira, Slack
  ui/     web UI (React, TypeScript, Vite), served by the app
  docs/   guides and architecture decision records
  evals/  building evaluation suites from historical tickets
  ```
- **Durability:** runs are a state machine in PostgreSQL with an append-only event log. Workers lease runs, so any
  instance can resume work after a restart.

## Roadmap
| Milestone | Scope |
|---|---|
| **M0–M4** ✅ | Skeleton and quality gates; run engine and API; Docker sandbox; model registry and agent loop; the full pipeline with gates |
| **M5–M7** ✅ | GitHub, GitLab, Bitbucket and Azure DevOps publishing; Jira intake; evaluation harness |
| **M8–M9** ✅ | Web UI; MCP server for AI clients |
| **M10** ✅ | One-command install, setup wizard, runtime connectors, Slack, built-in accounts, API tokens |
| **M11** ✅ | Claude Code engine, delivery insights, plan review option, hardening from live use |
| **Next** | The planner asks clarifying questions before writing the spec |
