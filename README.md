# Agentic SDLC

Turns a task (a prompt, a Jira ticket, or another source) into a reviewed pull request. An agent plans the work, implements it in an isolated Docker sandbox, runs the build and tests, and reviews its own changes. Humans approve at gates whose number scales with the task's risk. A human always approves the push, and merging is never automated.

> Status: **M1 (task API, run engine, live events)**. See [the roadmap](#roadmap).

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
| M2 | Docker sandbox, JGit clone, build/test detection |
| M3 | Spring AI model registry (per-role provider/model), tool loop with budgets |
| M4 | Triage → context → spec → implement ⇄ verify → review, all gates |
| M5 | SCM providers: GitHub, GitLab, Bitbucket, Azure DevOps (branch push + PR) |
| M6 | Jira intake (webhook + REST) and status comments |
| M7 | Evaluation harness built from historical tickets |
| M8 | Web UI |
