# REST API reference

Every endpoint of the v1 API, with the role it needs.

[← Back to the README](../README.md)

## API (v1)
All endpoints need a bearer token: a personal API token (`asdlc_…`), a JWT from the built-in sign-in, or a JWT from your OIDC provider (`spring.security.oauth2.resourceserver.jwt.issuer-uri`). The OpenAPI description is at `GET /v3/api-docs` (or `/v3/api-docs.yaml`), without a token. Roles are read from the `roles` claim, configurable with `agentic.security.roles-claim` (Keycloak: `realm_access.roles`). Errors are RFC 9457 problem details.

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/v1/tasks` | operator | Submit a task. An optional `Idempotency-Key` header makes retries return the original run. |
| `GET /api/v1/memory?repository=` | viewer | What agents learned about a repository: facts, citations, status. |
| `POST /api/v1/memory/{id}/status` | approver | `{"status": "ACTIVE"}` or `"DISABLED"`. |
| `GET /api/v1/insights?days=` | viewer | Delivery figures for the last 1–365 days (default 30): runs, outcomes, success rate, median/p90 minutes to pull request, cost, tokens, runs per day. |
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
| `POST /api/v1/auth/logout` | signed in | End every session of the caller (built-in sign-in). |
| `POST /api/v1/auth/password` | signed in | `{currentPassword, newPassword}`; returns a new token. |
| `GET /api/v1/tokens?all=` | signed in | Your API tokens (admins: `all=true` for everyone's); never the secret. |
| `POST /api/v1/tokens` | signed in | `{name, roles, expiresInDays}` → `{details, token}`; the token is shown once. |
| `DELETE /api/v1/tokens/{id}` | signed in | Revoke one of your tokens (admins: anyone's). |
| `GET/POST /api/v1/users`, `PUT /api/v1/users/{u}/roles`, `PUT …/password`, `DELETE /api/v1/users/{u}` | admin | Built-in accounts. |

Operations: `/actuator/health/{liveness,readiness}` and `/actuator/prometheus`, served on management port 8081 in the `prod` profile. Metrics: `agentic.stage.duration`, `agentic.run.transitions`, `agentic.stage.failures`, `agentic.step.abandoned`.
