# ADR-0003: Own Spring AI tool loop for the coding agent instead of embedding the Claude Agent SDK
- Status: Proposed
- Date: 2026-10-08

## Context
The IMPLEMENTING stage needs an autonomous agent that reads, edits, searches and runs commands in the target repository, inside a per-run Docker sandbox, until build and tests pass. It must:
- work with every configured provider (Claude, OpenAI, Bedrock, Azure, Vertex, Ollama);
- emit each tool call to the event log and UI;
- respect per-run token and cost budgets;
- stop cleanly on cancel;
- never perform external writes (push, PR, Jira). Those are deterministic orchestrator steps after a gate (ADR-0002).

## Options considered

### A. Our own loop on Spring AI
`ChatClient` with user-controlled tool execution and our own tool set: `view_file`, `str_replace` (unique match required), `create_file`, `grep`, `list_files`, `run_command`.

**Pros**
- Provider-agnostic.
- We see every turn: events, budgets, cancellation, iteration caps.
- Tools are designed for this sandbox, so no external writes are possible.
- Everything stays inside the JVM.

**Cons**
- We own the loop's quality: prompting, context compaction, output truncation, stuck detection.
- Will start weaker than a mature harness.

### B. Claude Agent SDK, or the Claude Code CLI headless, inside the sandbox
**Pros**
- Strongest coding loop out of the box: compaction, hooks, subagents, budgets (`maxTurns`, `maxBudgetUsd`).

**Cons**
- Claude only.
- Runs as a Node or Python process in the container, so the model key or a proxy must be reachable from the sandbox.
- Less control over event granularity.
- Another runtime in the sandbox image.

### C. Claude Managed Agents (hosted harness)
**Pros**
- No loop to maintain.
- Self-hosted sandbox option.

**Cons**
- Claude only. Beta.
- Not eligible for zero data retention.
- External dependency for core behaviour.

## Decision
Build **our own loop on Spring AI** behind a core `AgentRoles` port, which exposes no Spring AI types. Keep the port narrow so a Claude Agent SDK adapter can be added later as an alternative runtime without changing the orchestrator.

## Consequences

### Positive
- One loop for all providers.
- Every tool call is an auditable `TOOL_CALLED`/`TOOL_RESULT` event.
- Budgets and cancellation are enforced between turns.
- The agent cannot reach SCM or Jira because those tools don't exist.

### Negative
- We own:
  - iteration caps (≤ 3 implement/verify fix loops, ≤ 2 review loops);
  - per-turn output truncation;
  - context compaction for long sessions;
  - stuck detection (repeated identical calls).
- **Host safety when the host runs git on an agent-writable workspace.** JGit runs on the host, which has the credentials, against a workspace the sandbox can write. A planted `.git/hooks/*` or `filter.*`/`core.fsmonitor` entry in `.git/config` could then execute on the host. Mitigations:
  - keep the git directory **outside** the bind mount (`--separate-git-dir`);
  - never run hooks;
  - ignore repository-level config for filters and fsmonitor.
- File tools must not follow symlinks out of the workspace. Executing them *inside* the container avoids this; any host-side reads must resolve real paths against the workspace root.

### Follow-ups
- **M3:**
  - Confirm the Spring AI 2.0.1 API for user-controlled tool execution (`ToolCallingManager`, `internalToolExecutionEnabled`).
  - Implement the loop with budget checks and stuck detection.
  - Strip `\u0000` from tool output before it reaches `run_events` (Postgres rejects it).
- ArchUnit rule: classes exposing `@Tool` methods must not depend on SCM, JGit push or Jira write adapters.
- **M7:** compare our loop with a Claude Agent SDK adapter on the evaluation set before deciding whether to add it.
