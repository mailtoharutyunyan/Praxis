# Web UI, sign-in and accounts

The web UI, the sign-in modes and built-in account management (ADR-0007, ADR-0009).

[← Back to the README](../README.md)

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

**Accounts (built-in sign-in).** Admins add people under **Users**, choose their roles, reset passwords and remove them; the last admin cannot be removed or demoted. Everyone changes their own password under **API & AI CLI**. Signing out ends the session on every device, and so do password and role changes. Instances pick up sign-outs made on other instances within 30 s.

**Setup and Settings.** Until a code host and a model are configured, signed-in admins see the setup wizard and everyone else a notice. Admins manage connectors under **Settings**. Forms are generated from the connector catalog (`GET /api/v1/connectors`), so a new connector needs no UI change.

Security: the backend sends a strict Content-Security-Policy, and model output is rendered as sanitized Markdown.
```bash
./mvnw -Pui package -DskipTests            # jar with the UI (needs Node 24+)
cd ui && npm ci && npm run dev             # hot reload on :5173, proxied to the backend on :8080
cd ui && npm test                          # UI unit tests
```
