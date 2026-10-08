# ADR-0002: Own Postgres-backed run state machine instead of Temporal
- Status: Proposed
- Date: 2026-10-08

## Context
A run lasts minutes to days: triage, context, spec, implement ⇄ verify loops, review, publish. It stops at human gates (SPEC, IMPLEMENTATION, PUBLISH), and the number of gates depends on risk. Runs must survive restarts and deploys, wait indefinitely for approvals, and expose every step to a live UI and an audit trail. The service may run as several instances.

## Options considered

### A. Temporal (with `temporal-spring-ai`)
**Pros**
- Industry-standard durable execution: deterministic replay, durable timers, retries, Signals and Updates for approvals.
- Model and MCP calls become Activities.

**Cons**
- A separate cluster to operate (or a cloud subscription).
- Deterministic-workflow constraints on code.
- Temporal's own thread-based workers next to WebFlux.
- `temporal-spring-ai` requires Spring AI ≥ 1.1, and support for Spring AI 2.0 / Boot 4 is **unverified**.
- History-size limits for long tool loops.

### B. Our own state machine on Postgres
**Pros**
- No new infrastructure; Postgres is already the system of record.
- The run row *is* the state, and approvals are plain state transitions.
- Easy to query for the UI.
- The event log doubles as the audit trail.

**Cons**
- We own leasing, retries, timeouts and idempotency.
- No durable timers or compensation for free.
- Steps run at least once.

### C. Spring Statemachine or a workflow library
**Pros**
- A ready-made state machine DSL.

**Cons**
- Adds little over an enum transition table.
- Its persistence and reactive support don't match our R2DBC stack.
- Another dependency to upgrade.

## Decision
Use **our own state machine**:
- `RunState` holds the structural transition table.
- `Run` holds the semantic rules:
  - A gate opens only from its own stage, and only if the run's policy requires it.
  - A decision names its gate and can lead only to that gate's successors.
  - `NEEDS_HUMAN` stores the exact stage to resume.
  - Risk can only be raised.
- State is persisted in Postgres (`runs`, `run_events`; Flyway `V1`), and Postgres check constraints repeat the rules.

**Worker protocol:**
- Claim a working run with `SELECT … FOR UPDATE SKIP LOCKED` on `runs_claimable_idx`. Free or expired leases only, with the state list identical to `RunState.working()`.
- Set `lease_owner`/`lease_expires_at` and heartbeat while working. The heartbeat updates the lease columns only.
- Every write is fenced by `version`: insert with 0, `UPDATE … WHERE id = :id AND version = :expected`, +1 per update, and the repository returns the stored instance. A worker whose lease was taken over then fails to write and abandons the step.
- The state change and its events are written in one transaction. Events get `seq = last_event_seq + 1` under the run's row lock, so SSE replay by `Last-Event-ID` never misses an event.

## Consequences

### Positive
- No new infrastructure.
- Approvals, the UI stream and the audit trail all read the same tables.
- Postgres enforces the invariants as well as `Run`.
- Scales horizontally with leases.

### Negative
- **At-least-once steps.** A lease can expire mid-step, so every step must be idempotent:
  - PUBLISHING looks for an existing branch or PR before creating one.
  - Usage can be counted twice for a retried model call (acceptable; flag in metrics).
- No durable timers. Timeouts (gate expiry, stuck runs) need a periodic sweeper.
- More code for us to own and test.

### Follow-ups
- **M1:**
  - Repository with version fencing and single-transaction state+event writes.
  - Worker with claim, heartbeat and release, plus a test that restarts mid-run.
  - Sweeper for expired leases and stale runs.
- Two transitions have no driver yet:
  - `PR_OPEN → DONE` needs a webhook or poller for merged/closed PRs (M5).
  - `NEEDS_HUMAN` needs a resume API (M1).
- Revisit Temporal if we need durable timers, sagas or compensation, or runs that span many days with many steps.
