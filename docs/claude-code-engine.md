# Agents on Claude Code

Running the agents on the Claude Code CLI with a Claude subscription instead of a model API key (ADR-0008).

[← Back to the README](../README.md)

## Agents on an AI CLI (Claude Code)
Agents can run on [Claude Code](https://code.claude.com/docs) instead of this app's own loop, with a Claude subscription (Pro, Max, Team or Enterprise) or an Anthropic API key ([ADR-0008](adr/0008-ai-cli-agent-engine.md)). Gates, verification, review, budgets and the live activity feed work as before.

Setup with Docker Compose:
1. Install the CLI into the sandboxes' tools volume. This is a one-off; run it again after changing `CLAUDE_CODE_VERSION`:
   ```bash
   docker compose --profile claude-code up cli-installer
   ```
   It downloads the pinned native build (`CLAUDE_CODE_VERSION`, default 2.1.282; 2.1.259 or later is required), checks it against the SHA-256 in the release manifest and puts it in the `agentic-tools` volume. Every sandbox mounts that volume read-only at `/opt/agentic-tools`.
2. On your own machine, run `claude setup-token` and copy the token it prints. It is valid for a year and can only make model requests. An Anthropic API key works too.
3. In **Settings → AI model**, set **Agent engine** to `claude-code` and paste the token as **Claude Code token**. **Model**, **Planning model**, **Review model** and **Triage model** become `--model`. Prices are not needed: Claude Code reports each call's cost, and that counts against the run's cost limit.
4. Optional: add an **API key** for the provider. Tasks from Jira, Slack, MCP and other untrusted sources then run on the API engine. Without one they wait for a human (see below).

Outside Compose, mount the CLI with `agentic.agent.cli.tools-dir` (a directory on the Docker host holding `claude`) or `tools-volume`. Without a model connector saved in the UI, set `agentic.agent.cli.enabled=true` and `agentic.agent.cli.token` (default `${CLAUDE_CODE_OAUTH_TOKEN}`). Other settings: `model`, `binary` (default `/opt/agentic-tools/claude`), `timeout` (20m per call), `max-turns` (60) and `untrusted-tasks`. The sandbox needs the network with the egress proxy (`dev/egress`), which allows `api.anthropic.com`. With `agentic.sandbox.network=none`, stages escalate and say why.

How it works:
- Each agent call runs `claude -p --output-format stream-json --verbose` in the run's main container. The brief comes from a file on standard input; the instructions come through `--append-system-prompt-file`. Scratch files live under `/tmp/.agentic-cli`, never in the workspace, and are removed afterwards.
- The CLI gets the least each role needs:
  - triage: no tools and one turn;
  - planner, spec critic and reviewer: Read, Glob and Grep;
  - test writer: edits only to test paths, with no shell. The stage checks the diff afterwards: if any other file changed, all of its changes are put back and the coder starts without tests-first.
  - coder: Read, Glob, Grep, Edit, Write and Bash.
- Every other tool use is denied, not asked about (`--permission-mode dontAsk`). The repository cannot configure the CLI: no project settings, hooks or MCP servers are loaded.
- The streamed transcript becomes the same run events as the API loop (agent messages, tool calls, tool results), with usage recorded as it is spent.
- `--max-turns` limits turns, and `--max-budget-usd` gets what the run may still spend. The app kills the CLI once the call's tokens exceed what is left of the run's token budget.
- Repository memory works too: the CLI writes the facts it learns to a file outside the repository (the only file a read-only call may write), and each is stored through the same `remember` tool, with the same citation check and limits.
- Triage needs the sandbox on this engine, so the sandbox starts during triage, before PREPARING_CONTEXT.

Security trade-offs:
- **Where the token goes.** It is set only in the environment of the command that runs the CLI: never in the container's environment, a command line, a log or an event. Anything recorded has it replaced with `[REDACTED]`, and a change that contains it fails the stage. Telemetry, error reporting and auto-updates are off. `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB` strips credentials from the CLI's tool subprocesses, but it needs bubblewrap in the toolchain image (Claude Code refuses to start without it), so it is turned on only where `bwrap` exists. In the stock Maven and Node images it is not, and the coder's shell commands can see the token; use an image with bubblewrap installed to close that.
- **The coder can still read it.** The coder's shell runs as the same user as the CLI, so it can read the token from the CLI's process. That is why tasks from untrusted sources never run on Claude Code: they use the API engine, or escalate if no API model is configured. `agentic.agent.cli.untrusted-tasks=true` lifts this rule, and you then accept that a prompt-injected ticket could try to exfiltrate the token.
- **What the network allows.** `api.anthropic.com` is reachable from sandboxes, so code running there could send data to the Anthropic API with a key of its own. Remove it from `dev/egress/allowed-domains.txt` if no agent runs on Claude Code.
- **Terms of use.** Check that your Claude plan's terms allow automated use of a subscription token in your setting.

Limits:
- Claude only.
- No stuck detection beyond the turn limit.
- The cost of a call killed for its token budget is unknown; its tokens still count.
- One CLI build serves every sandbox. The default build needs glibc. For Alpine (musl) toolchain images, install the musl build (`CLAUDE_CODE_PLATFORM=linux-x64-musl` or `linux-arm64-musl`); those images need `libgcc`, `libstdc++` and `ripgrep`, and then glibc images cannot run it.
