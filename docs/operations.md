# Deployment and operations

Running the app outside the bundled Compose stack, scaling to several instances, day-2 operations and supply-chain controls.

[← Back to the README](../README.md)

## Deployment
The `Dockerfile` builds one image with the API and the web UI. It runs as uid 10001 with the `prod` profile.
```bash
docker build -t agentic-sdlc .
```
What the container needs:
- **Postgres:** `SPRING_R2DBC_URL` (plus username and password) and `SPRING_FLYWAY_URL` (plus user and password).
- **Sign-in:** `AGENTIC_SECURITY_MODE=local` for built-in accounts (see [Web UI](web-ui.md)), or an OIDC issuer and an audience:
  - `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`.
  - `AGENTIC_JWT_AUDIENCE`, default `agentic-sdlc`. Access tokens must carry this value in `aud`; in Keycloak, add an audience mapper.
- **Docker access for sandboxes:** `DOCKER_HOST`, or the Docker socket mounted with `--group-add <docker gid>`.
  - Access to the Docker API is root on that host. Use a dedicated sandbox host, or rootless Docker.
  - Sandboxes must see the run's files. Either mount a Docker volume at the workspace root and name it in `AGENTIC_SANDBOX_WORKSPACEVOLUME`, so sandboxes mount their run's directory from it as a volume subpath (Docker Engine 26+; this is what `compose.yaml` does), or bind-mount a host directory at the same path on the host and in the container. The default path is `/var/lib/agentic/workspaces`, and it must be owned by uid 10001.
- **Model and SCM credentials** as environment variables, such as `ANTHROPIC_API_KEY` and `agentic.scm.tokens`.

Several instances can share one database:
- Each run's working copy stays on the node that holds it (`agentic.worker.node-id`, default: the host name). Keep the node id stable across restarts, for example with a StatefulSet.
- If a node stops sending heartbeats for `node-timeout` (90 s), its runs move to other nodes. Their working copies are then lost: publishing refuses a diff that no longer matches the approved one, and the run goes to a human.
- Jira comments and pull request polling hold a cluster-wide lease, so only one instance runs each.
- On shutdown, a worker stops claiming runs and gives in-flight steps `drain-timeout` (20 s) to finish. Steps still running after that release their lease, so another instance picks them up immediately.


### Operations
- **Rate limits.** Per client address and instance: 10 sign-in attempts a minute, 600 webhook calls and 1200 other API or MCP calls (`agentic.rate-limit.*`), answered with 429 and `Retry-After`. Five wrong passwords in a row lock an account for a minute. Behind a proxy, set `server.forward-headers-strategy=framework` so the real client address counts.
- **Retention.** Finished runs (done, failed, cancelled) are deleted with their events after `agentic.retention.finished-runs` (180 days; `0` keeps them). Facts learned in them stay. Revoked and expired API tokens are deleted after 30 days.
- **Secrets key rotation.** Put the new key in `AGENTIC_SECRETS_KEY` and the old one in `AGENTIC_SECRETS_KEY_PREVIOUS` (comma-separated for several), then restart. Stored secrets are re-encrypted with the new key on startup, and the log says when the old key can be removed.
- **Backups.** The Compose stack writes a nightly `pg_dump` into the `agentic-backups` volume and keeps `BACKUP_KEEP_DAYS` (14). Copy them out with `docker compose cp backup:/backups ./backups`, and restore with `docker compose exec -T postgres pg_restore -U agentic -d agentic_sdlc --clean < backups/<file>.dump`. Back up the secrets key as well.
- **Health.** The app container reports healthy once `/actuator/health/readiness` on the internal port 8081 is `UP`.
- **Logs.** Every line logged while a run is processed carries its id (`runId` in the MDC, a field in the JSON logs).
- **Stronger sandbox isolation.** Set `AGENTIC_SANDBOX_RUNTIME=runsc` once [gVisor](https://gvisor.dev/docs/user_guide/install/) is installed on the Docker host, so sandboxes, sidecars and scanners run under its user-space kernel. Rootless Docker limits what the Docker API grants; access to it is otherwise root on the host.


### Supply chain
- **Pinned actions.** Every GitHub Action in `.github/workflows/` is pinned to a full commit SHA, with its version in a trailing comment.
- **Dependabot.** `.github/dependabot.yml` proposes weekly updates for GitHub Actions, Maven, npm (`ui/`) and the Dockerfile's base images. A release must be 14 days old before it is proposed; security updates are not delayed.
- **CodeQL.** `.github/workflows/codeql.yml` analyses the Java code and the UI on pushes to `main`, on pull requests and weekly.
- **SBOM.** `./mvnw package` writes a CycloneDX SBOM of the application to `app/target/bom.json`. CI uploads it as the `sbom-app` artifact, and the UI's SBOM (runtime dependencies from `ui/package-lock.json`) as `sbom-ui`.
- **Image scan.** CI builds the production image and scans it with [Trivy](https://trivy.dev). A critical vulnerability with a fix available fails the build.
