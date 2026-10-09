# Workspaces, sandbox and microservices

How each run gets an isolated working copy and container, how toolchains are detected, and how monorepos, sidecars and multi-repository changes work (ADR-0006).

[← Back to the README](../README.md)

## Workspaces and the sandbox
Each run gets a working copy on the host and one Docker container:
- `<workspace-root>/<run>/repo` is the work tree, mounted at `/workspace` in the container.
- `<workspace-root>/<run>/git` is the git metadata. It is never mounted and hooks are disabled, so nothing the agent writes can run on the host, where the SCM credentials live.

Container hardening:
- all capabilities dropped and `no-new-privileges`;
- runs as a non-root user (the app refuses to start a sandbox as root);
- memory, CPU and process limits;
- no network by default, never the host's network;
- no credentials in the environment (with the Claude Code engine, its token is set for the CLI's own command only; see below);
- every command is killed by `timeout` when it runs too long.

The coder cannot change how its work is checked. `.agentic-sdlc.yml`, `AGENTS.md`/`CLAUDE.md` and the build-file detection are read from the base commit, not from the working tree. Publishing pushes only the diff that was approved at the PUBLISH gate, checked by SHA-256.

The toolchain is detected from root files: Maven, Gradle, npm/pnpm/yarn, Go, Python and .NET. A repository can override it, or declare a custom one, in `.agentic-sdlc.yml`:
```yaml
image: maven:3.9-eclipse-temurin-21
setup: ./mvnw -B -ntp dependency:go-offline   # optional
build: ./mvnw -B -ntp -DskipTests test-compile
test:  ./mvnw -B -ntp verify
```

### Microservices: monorepos, mixed toolchains, sidecars
See ADR-0006.
- **Services.** If the repository has no root build, every top-most directory (up to 3 levels deep) with a recognised build file is a service, and each gets a sandbox container from its own toolchain image. Node, Java, Go, Python and .NET can share a repository.
- **Scoped verification.** Only the services a change touches are built and tested. A change outside every service (shared files) verifies all of them. The baseline at context preparation builds every service.
- **Agent commands.** `run_command` takes `service: <name>` to run in that service's toolchain from its directory.
- **Explicit list.** `.agentic-sdlc.yml` can name the services instead of relying on detection. It can also declare **sidecars**, the containers the tests need, on a private per-run network with no internet; their names are the hostnames:
```yaml
services:
  - { name: orders, path: services/orders }                         # toolchain detected from its files
  - { name: web, path: apps/web, image: node:24-bookworm, test: npm test -- --ci }
sidecars:
  - { name: postgres, image: postgres:16-alpine, env: { POSTGRES_PASSWORD: test }, ready: pg_isready -U postgres }
  - { name: kafka, image: apache/kafka:4.1.0 }
env:                                                                 # for every service's build
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/postgres
```
**Changes across repositories.** A task can name **companion repositories**, for example an API's consumers, through `companions` in `POST /api/v1/tasks`, `companionRepositories` in the MCP `submit_task` tool, the UI's new-task dialog, or `agentic.jira.projects.<KEY>.companions`.
- **Layout.** Each companion is checked out at `/workspace/.repos/<alias>/` with its own `agent/<run>` branch. The agent changes all repositories together, and the diff and the PUBLISH approval cover them all.
- **Publishing.** Every repository that changed gets its own pull request. The pull requests reference one another and are cross-linked by a comment.
- **Completion.** The run is DONE when all of them are merged, and CANCELLED once none is open but not all were merged.
- **Feedback.** Review comments and CI failures on any of the pull requests send the run back for a revision.

**API contracts.** A change to an OpenAPI, AsyncAPI, protobuf, GraphQL, Avro or WSDL file is flagged to the reviewer, at the gates ("Checks") and in the pull request, with a reminder to check consumers.

Sidecars don't see the code and get no credentials. Testcontainers can't run inside the sandbox, because that would need the Docker socket; declare the same containers as sidecars and point the tests at them through `env`.

SCM settings (`agentic.scm.*`):
- `allowed-hosts`: the hosts tasks may point at (SSRF guard).
- `tokens."[host]"`: per-host access tokens from the environment.
- `mirrors`: URL rewrites, like git's `insteadOf`.
- `clone-depth`: default 1.

Sandbox settings (`agentic.sandbox.*`): `network`, `egress-proxy`, `no-proxy`, `user`, `memory`, `cpus`, `command-timeout`.

**Egress.** `network: none` (the default) blocks all network access, so builds must not download anything. To allow dependency downloads without opening the network, use the allowlisting proxy in `dev/egress`. It is a Squid proxy on an internal Docker network whose only way out is that proxy.
```bash
docker compose -p agentic-egress -f dev/egress/compose.yaml up -d
```
```yaml
agentic.sandbox:
  network: agentic-sandbox
  egress-proxy: http://egress:3128   # exported as HTTP(S)_PROXY, JVM proxy properties and Maven settings
```
Package registries (Maven Central, Gradle, npm, PyPI, Go, NuGet) are reachable through the proxy. Cloud metadata, internal hosts and everything else are refused. Edit `dev/egress/allowed-domains.txt` to change the list. The `local` profile uses `bridge` for convenience; never use it for ticket-sourced tasks.
