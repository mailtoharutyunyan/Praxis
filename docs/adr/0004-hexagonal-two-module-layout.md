# ADR-0004: Hexagonal two-module layout enforced by the build and ArchUnit
- Status: Proposed
- Date: 2026-10-08

## Context
The system has a stable core (tasks, runs, gates, the state machine) and many replaceable edges:
- intake: prompt API, Jira, issues, Slack;
- LLM providers;
- sandbox runtimes (Docker now, gVisor or Kubernetes later);
- four SCM providers;
- persistence and the UI stream.

The rules that matter most (gates cannot be skipped; external writes only after approval) must not depend on, or be bypassed through, any framework or adapter.

## Options considered

### A. Single module, package conventions only
**Pros**
- Simplest build.

**Cons**
- Nothing stops a core class from importing Spring, R2DBC or an adapter.
- Layering erodes silently.

### B. Single module and ArchUnit
**Pros**
- Rules run in the test suite.

**Cons**
- Core can still *compile* against any dependency on the classpath.
- Violations surface only at test time.

### C. Two Maven modules (`core`, `app`), plus Enforcer and ArchUnit
**Pros**
- The compiler enforces that core cannot see Spring or adapters.
- Enforcer `bannedDependencies` on `core` fails the build if any compile or runtime dependency other than Reactor is added (checked against `spring-core`).
- ArchUnit covers what modules can't: rules inside `app` and allowlists inside `core`.

**Cons**
- One more POM.
- Persistence rows and domain records are mapped separately, because core cannot carry `@Version` or other annotations.

### D. Module per adapter (`adapter-jira`, `adapter-github`, …)
**Pros**
- Strongest isolation.
- Each adapter has its own dependencies.

**Cons**
- Premature at this size.
- Slower builds and more boilerplate.
- Can be split out of `app` later along the same package boundaries.

## Decision
Use **option C**:
- **`core`** contains domain records, the state machine and ports. It depends only on the JDK and Reactor.
- **`app`** contains Spring Boot, `adapter.in.*` (web, webhooks), `adapter.out.*` (persistence, llm, sandbox, scm, jira) and `config`.

ArchUnit (`ArchitectureTest`) enforces these rules:
- `core` depends only on `java..`, `reactor..` and `org.reactivestreams..`. `core.domain` depends only on `java..`.
- `core` slices are free of cycles.
- Inbound adapters never depend on outbound ones, and the reverse.
- Outbound adapters don't depend on each other.
- All production code lives in `core`, `adapter` or `config`, apart from the bootstrap class.

`SchemaContractTest` keeps the Flyway check constraints and the claimable-runs index in step with the core enums.

## Consequences

### Positive
- The gate and transition rules live in plain Java, unit-tested without Spring (81 tests at M0).
- Adapters are swappable, and a new provider is a new package plus configuration.
- Layering violations fail the build, not code review.

### Negative
- Row-to-record mapping code in the persistence adapter.
- Ports must be designed carefully. `AgentRoles` must not leak Spring AI types; `ScmProvider` must cover four providers.
- Adapter rules use `allowEmptyShould(true)` until the adapter packages exist, so a typo could silently disable a rule.

### Follow-ups
- **M1:** remove `allowEmptyShould(true)` once `adapter.in.web` and `adapter.out.persistence` exist.
- **M1:** add BlockHound to integration tests to catch blocking calls on event-loop threads.
- **M3:** add the "`@Tool` classes never reach SCM, JGit push or Jira write" rule (ADR-0003).
- Split adapters into their own modules if `app` build time or dependency conflicts become a problem.
