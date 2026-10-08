# Agentic SDLC

Turns a task (a prompt, a Jira ticket, or another source) into a reviewed pull request. An agent plans the work, implements it in an isolated Docker sandbox, runs the build and tests, and reviews its own changes. Humans approve at gates whose number scales with the task's risk. A human always approves the push, and merging is never automated.

> Status: **M0 (skeleton)**. See [the roadmap](#roadmap).

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

## Roadmap
| Milestone | Scope |
|---|---|
| **M0** ✅ | Multi-module skeleton, schema, CI, ADRs, ArchUnit, coverage gate |
| M1 | Task intake API, run engine and leased worker, SSE event stream, approvals, OAuth2 roles |
| M2 | Docker sandbox, JGit clone, build/test detection |
| M3 | Spring AI model registry (per-role provider/model), tool loop with budgets |
| M4 | Triage → context → spec → implement ⇄ verify → review, all gates |
| M5 | SCM providers: GitHub, GitLab, Bitbucket, Azure DevOps (branch push + PR) |
| M6 | Jira intake (webhook + REST) and status comments |
| M7 | Evaluation harness built from historical tickets |
| M8 | Web UI |
