# ADR-0006: Microservice repositories

- Status: Accepted
- Date: 2026-10-08

## Context
Until now a run assumed one repository with one toolchain at its root: the build was detected from root files, and one sandbox image ran every command. Microservice codebases break each of those assumptions:
- **Monorepos.** Services live in subdirectories, each with its own build file (`services/orders/pom.xml`, `services/web/package.json`), and there is often no root build.
- **Mixed toolchains.** One repository holds Java, Node, Go and Python services, which need different images.
- **Service dependencies.** Tests need a database, a broker or a cache. The sandbox has no network and no Docker, so Testcontainers cannot run inside it.
- **Polyrepos.** One change spans repositories, for example an API provider and its consumers.
- **Cost of full builds.** Verifying everything for a one-service change is slow and expensive.

## Options considered
1. **One devcontainer image for everything, configured by the repository.**
   - Pros: simple.
   - Cons: it pushes all the work onto every repository, gives no per-service verification, and still has no databases.
2. **Run each service as its own task.**
   - Cons: loses atomic cross-service changes and duplicates gates and reviews.
3. **A build plan of components, each with its own toolchain container, plus sidecars and companion repositories in one run (chosen).**

## Decision
- **Build plan.** A run's working copy has a `BuildPlan`: one or more components (services), each a path plus a `BuildProfile`.
  - A root build file still means one component at `.`.
  - Otherwise each top-most directory, down to 3 levels, that has a recognised build file is a component.
  - `.agentic-sdlc.yml` can list `services:` explicitly (name, path, image, setup, build, test).
- **One container per component.** Each component gets its own container from its image, all mounting the same `/workspace`.
  - Commands run in the component's container from its directory.
  - The agent's `run_command` takes an optional `service`.
- **Verification is scoped to the change.**
  - Only components containing changed files are built and tested.
  - A change outside every component (shared files) verifies all of them.
  - The baseline build at context preparation covers all components.
- **Sidecars.** `.agentic-sdlc.yml` `sidecars:` declares dependency containers (e.g. `postgres:16`) with an optional readiness command.
  - They run on a per-run internal Docker network (no egress) that the component containers join; the sidecar name is the hostname.
  - `env:` passes connection settings to the builds.
  - Sidecars do not mount the workspace and get no credentials. They keep the image's default capabilities, because database entrypoints need them, and run with `no-new-privileges` and resource limits.
  - Testcontainers inside the sandbox remains unsupported (that would need the Docker socket); repositories use sidecars for agent runs instead.
- **Companion repositories.** A task may name companion repositories.
  - They are cloned into `/workspace/.repos/<alias>/`, each with its own git directory on the host and its own `agent/<run>` branch.
  - `.repos/` is excluded from the primary repository.
  - Diffs and the PUBLISH fingerprint cover all repositories.
  - Publishing pushes and opens a pull request in each repository that changed, and cross-links them.
  - Revisions and CI fixes work from any of the pull requests. The run is DONE when every pull request is merged, and CANCELLED once none is open but not all were merged.
- **API contract changes.** Changes to API contract files (OpenAPI, AsyncAPI, protobuf, GraphQL, Avro) are flagged to the reviewer, at the gates and in the pull requests.

## Consequences
- Positive: monorepos, polyglot repositories, services that need infrastructure for tests, and cross-repository changes work without a custom image per repository. Verification cost scales with the change.
- Negative:
  - more containers per run;
  - sidecars widen what runs next to the sandbox (mitigated by the internal network and no workspace mount);
  - multi-repository publishing is not atomic, since one push can fail after another succeeded. It is idempotent, so a retry completes it.
- Follow-ups: dependency-graph-aware selection of affected services (a change to a shared library verifies its dependents), and contract compatibility checks (e.g. `buf breaking`, `oasdiff`).
