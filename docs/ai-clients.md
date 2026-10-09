# AI clients and the MCP server

Driving the app from Claude Code, Codex CLI, Gemini CLI, Cursor or any MCP client (ADR-0005, ADR-0009).

[← Back to the README](../README.md)

## MCP: use it from an AI CLI or any AI client
`/mcp` is an MCP server (Streamable HTTP, stateless; ADR-0005). From Claude Code, Codex CLI, Gemini CLI, Cursor or another MCP client you can hand over work and follow it in chat: "add a /health endpoint to acme/shop and tell me when it waits for approval".

**Quickest way: a personal API token.** Open **API & AI CLI** in the web UI, create a token (name, roles, expiry), and copy the ready-made command for your CLI:
```bash
# Claude Code
claude mcp add --transport http --scope user agentic-sdlc https://agentic.example.com/mcp \
  --header "Authorization: Bearer asdlc_…"
# Codex CLI
export AGENTIC_SDLC_TOKEN=asdlc_…
codex mcp add agentic-sdlc --url https://agentic.example.com/mcp --bearer-token-env-var AGENTIC_SDLC_TOKEN
# Gemini CLI
gemini mcp add --scope user --transport http --header "Authorization: Bearer asdlc_…" agentic-sdlc https://agentic.example.com/mcp
```
API tokens:
- **Scope.** A token acts as its owner with some of the owner's roles (`viewer`, `operator`, `approver`), never `admin`. It cannot change settings, manage users or mint more tokens.
- **Lifetime.** Every token expires (at most `agentic.security.api-token-max-ttl`, 365 days) and can be revoked; deleting a user revokes theirs.
- **Storage.** Only a SHA-256 of the token is stored, and the UI shows it once.
- The same token works for the REST API (`Authorization: Bearer asdlc_…`), in scripts and CI.

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

**Authentication with an identity provider.** Clients can also send the user's OIDC access token, which needs the same roles as the API.
- Clients that support MCP OAuth find the identity provider on their own. A 401 points to `/.well-known/oauth-protected-resource`, which names the issuer.
- Tokens must carry this API's audience. If your IdP sets `aud` to the resource URL (RFC 8707), add `https://<host>/mcp` to `AGENTIC_JWT_AUDIENCE`, comma-separated.
- Set `agentic.mcp.resource` to the public `/mcp` URL when behind a proxy, and `agentic.mcp.run-link-base` (`https://<host>/#/runs/`) for links.

```json
// Cursor (.cursor/mcp.json) and other clients that take a URL plus headers
{ "mcpServers": { "agentic-sdlc": { "url": "https://agentic.example.com/mcp",
    "headers": { "Authorization": "Bearer ${TOKEN}" } } } }
```
Claude Desktop and claude.ai: add a custom connector with the `/mcp` URL and sign in through your IdP. Locally, use `scripts/dev-token.sh` for a token, and inspect the server with `npx @modelcontextprotocol/inspector@2.8.0`.
