# ADR-0005: MCP server for AI clients

- Status: Accepted
- Date: 2026-10-08

## Context
Users want to hand work to the pipeline from the AI tools they already use: Claude Desktop and Claude Code, ChatGPT, Cursor, IDE agents. Each of these speaks the Model Context Protocol (MCP). The app already has a REST API with JWT authentication and roles (viewer, operator, approver), and its own agents (ADR-0003) do the actual work in a sandbox.

The product's core promise is that humans approve the risky steps (the SPEC, IMPLEMENTATION and PUBLISH gates) and a human merges. An AI client acts with its user's token, and it can be steered by text it read elsewhere (prompt injection).

## Options considered
1. **A separate MCP server process that calls the REST API.**
   - Pros: independent release cycle; can also run over stdio.
   - Cons: a second deployable; credentials have to be relayed or a service account used; two places to keep in sync.
2. **An MCP endpoint inside the app (Spring AI MCP server, Streamable HTTP).**
   - Pros: reuses the same security filter chain, roles and core services as the REST API; one deployment.
   - Cons: depends on Spring AI's MCP server starter (the only Spring AI starter in the build).
3. **Replace the internal agents with MCP clients driving the sandbox.**
   - Rejected: it gives up the controlled agent loop, budgets and the Rule of Two (ADR-0003).

## Decision
Option 2.
- **Transport:** `spring-ai-starter-mcp-server-webflux` with `protocol: STATELESS` and `type: ASYNC`, at `/mcp`.
  - Stateless means no session affinity, so it works across instances.
  - Tools return `Mono`, so nothing blocks the event loop.
- **Authentication:** same as the API (bearer JWT, audience-checked).
  - `/mcp` admits any API role, and each tool checks the role it needs: viewer for reads, operator for submit, cancel and resume.
  - Discovery follows the MCP authorization spec: `/.well-known/oauth-protected-resource` (RFC 9728), plus `resource_metadata` in the 401 challenge, so clients can find the IdP themselves.
- **Tools:** `submit_task`, `list_runs`, `get_run`, `get_run_artifact`, `get_run_events`, `cancel_run`, `resume_run`.
  - Each carries read-only and destructive hints.
  - `get_run` says in words what happens next.
- **No gate decisions over MCP.** Approving a spec, an implementation or a push stays with a human in the UI or API.
- **Tasks from MCP are untrusted** (`TaskOrigin.MCP`). Untrusted tasks always stop at the SPEC gate, and the task text is framed as data for the models.

## Consequences
- Positive: any MCP client can submit and follow runs with the user's own identity and permissions. No new deployable, no new credentials.
- Negative:
  - AI-submitted work always needs a spec approval, even for low-risk changes. This is intended, but slower.
  - The stateless server cannot stream progress or ask the user questions (no sampling or elicitation); clients poll `get_run`.
- Follow-ups:
  - The IdP must issue tokens with this API's audience to MCP clients. For RFC 8707 resource indicators, add the `/mcp` URL to `AGENTIC_JWT_AUDIENCE`.
  - Consider MCP resources for specs and diffs, and an approver-only tool that only adds gates (raise risk).
