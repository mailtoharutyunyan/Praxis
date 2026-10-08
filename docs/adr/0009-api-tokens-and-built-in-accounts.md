# ADR-0009: Personal API tokens and built-in accounts

- Status: Accepted
- Date: 2026-10-09

## Context
ADR-0007 added a built-in sign-in so the Compose install works without an identity provider. Using it showed gaps:
- **Claiming a fresh install.** Whoever reached a fresh install first could create the admin account.
- **Sign-out.** Signing out only forgot the token in the browser; the token stayed valid until it expired.
- **Accounts.** There was a single account, with no way to add people or change passwords.
- **Lockout.** Failed sign-ins were counted per instance, in memory.

People also want to drive the app from AI CLIs (Claude Code, Codex, Gemini CLI) and scripts. The MCP server (ADR-0005) accepts bearer JWTs, but a browser session token is short-lived and was never meant to be pasted into a CLI's configuration.

## Options considered
1. **Use session JWTs for CLIs too.** Nothing new to build. But they expire within hours, and they carry every role of the user, admin included.
2. **OAuth for MCP clients through the built-in sign-in** (an authorization server in the app). This is the standard way. It is a large piece of security-critical code, and not every CLI supports it yet.
3. **Personal API tokens.** These are random secrets that a user creates in the UI. They are scoped to some of the user's roles, expire and can be revoked, and only a hash is stored. Every CLI can send a bearer header, and this is how GitHub, GitLab and Atlassian handle the same need.

## Decision
Option 3, together with hardening the built-in accounts.

**API tokens**
- **Format.** A token is `asdlc_` followed by 32 random bytes (base64url). Only its SHA-256 is stored, together with a short non-secret hint for lists.
- **Scope.** A token gets a subset of its owner's `viewer`, `operator` and `approver` roles, never `admin`. It lives at most 365 days, can be revoked, and is revoked when its owner is deleted.
- **Recognition.** A bearer token starting with `asdlc_` is looked up in the database; any other bearer token is a JWT. Both become a `JwtAuthenticationToken` whose subject is the owner, so every existing rule applies unchanged. A `token_id` claim marks API tokens.
- **What a token cannot do.** Endpoints that manage tokens, sessions and users refuse API tokens: a leaked token cannot mint another one or lock its owner out.

**Built-in accounts**
- **Setup code.** The first admin needs a one-time code that the app logs on start (or `AGENTIC_SETUP_CODE`), so only someone with access to the server can claim the install.
- **Lockout.** Five consecutive wrong passwords lock an account for a minute. The count lives in the database, so all instances share it.
- **Ending sessions.** Each user has `sessions_valid_after`. Sign-out, a password change, a role change or deletion moves it forward, and tokens issued earlier are rejected. Instances keep a cache refreshed every 30 seconds, and a miss reads the database.
- **Administration.** Admins manage users and roles. The last admin cannot be removed or demoted.

**Rate limits.** Token buckets are kept per client address on each instance and run in front of authentication: tight for sign-in and setup, generous for the API and MCP.

## Consequences
- Positive:
  - Claude Code, Codex and Gemini CLI connect to `/mcp` with one command, which the UI generates.
  - Scripts and CI use the same tokens.
  - Fresh installs cannot be claimed by strangers.
  - Sign-out really ends sessions.
- Negative:
  - Each API-token request costs one indexed database lookup.
  - Sign-outs reach other instances only after up to 30 seconds.
  - Rate limits are counted per instance, so N instances allow N times the configured rate.
  - API tokens are bearer secrets; anyone holding one acts as its owner until it expires or is revoked.
- Follow-ups:
  - OAuth for MCP clients through the built-in sign-in, when the CLIs support it widely.
  - Per-token usage history.
