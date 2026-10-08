# ADR-0007: Onboarding, local sign-in and runtime connectors

- Status: Accepted
- Date: 2026-10-09

## Context
Every integration (code host tokens, model keys, Jira, webhook secrets) was configured through properties and environment variables at deploy time, and sign-in required an external OIDC provider. Starting the app with one `docker compose up` and setting it up from the browser needs three things:
- a first-run wizard;
- sign-in without an identity provider;
- integrations ("connectors") that can be added, changed and skipped at runtime, including Slack.

## Decision
- **Connectors.** Each connector stores its plain settings as JSON and its secrets encrypted with AES-256-GCM. The connectors are the git hosts, the AI model provider, Jira, Slack and pull-request webhooks.
  - The key comes from `AGENTIC_SECRETS_KEY`. Failing that, it is generated into the data directory on first start; that fallback is meant for local installs.
  - Every instance keeps a decrypted in-memory snapshot, refreshed on change and every 30 s.
  - Consumers (SCM HTTP and git credentials, allowed hosts, model registry, Jira, Slack, webhook secrets) read the snapshot at call time. Values from properties stay as the fallback, so existing deployments keep working.
- **Onboarding.** `GET /api/v1/setup` (public, no secrets) reports the steps.
  - Required: an admin account (local mode only), a git host, and an AI model.
  - Optional: Jira, Slack, pull-request webhooks.
  - The UI shows the wizard until every required step is done and every optional one is done or skipped. Each connector has a live "Test connection" check.
- **Local sign-in.** `agentic.security.mode=local` (used by the Compose stack) signs users in with a username and password: BCrypt hashes, and a short lockout after repeated failures.
  - Successful sign-in returns an RS256 JWT from an app-generated key pair (private key encrypted at rest).
  - The resource server validates these tokens like OIDC ones (issuer, audience, expiry), so roles and every API rule are unchanged.
  - The first admin is created during setup. `oidc` stays the production default.
- **Administration.** A new `admin` role manages connectors; the first local user has every role.
- **Packaging.** The root `compose.yaml` runs the app (with the UI), Postgres, the egress proxy and a Docker socket proxy.
  - The app reaches Docker only through the socket proxy (`tcp://docker:2375`) on an internal network.
  - The workspace is a named volume. Sandboxes and scanners mount their run's directory from it as a volume subpath (Engine API 1.45+), because a path inside the app container means nothing to the Docker daemon.
  - Sandbox containers carry the workspace identity as a label, so cleanup never removes another installation's containers on a shared Docker host.

## Consequences
- Positive:
  - One command and a browser are enough to start.
  - Integrations can change without a redeploy.
  - Secrets are never returned by the API, only whether they are set.
- Negative:
  - Secrets now also live in the database (encrypted), so the key must be protected and backed up with it.
  - Local sign-in is a small built-in identity store; production should keep using OIDC.
  - Volume subpaths need Docker Engine 26 or later.
- Follow-ups: user management for local sign-in (more accounts, roles, password change).
