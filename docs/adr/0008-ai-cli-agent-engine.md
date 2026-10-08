# ADR-0008: Agents on an AI CLI (Claude Code) as an optional engine

- Status: Accepted
- Date: 2026-10-09

## Context
Every agent stage runs our own loop (ADR-0003) against a provider's API, paid per token with an API key. Many teams already pay for Claude subscriptions and use Claude Code every day. They want the pipeline to run on that harness and that subscription, with its compaction, editing tools and prompts, rather than only on raw API keys. ADR-0003 rejected embedding a CLI as the *only* loop: it works with one vendor, puts a credential near untrusted code, and gives less control over events. As an *option*, with those costs contained, it is worth having.

Facts this design rests on, checked against the Claude Code docs (code.claude.com) in October 2026:
- `claude -p` runs headless and reads the prompt from standard input. `--output-format stream-json --verbose` prints one JSON message per line: `system`/`init`, then `assistant` messages (text and `tool_use` blocks) and `user` messages (`tool_result` blocks), and finally a `result` message. The result carries `subtype` (`success`, `error_max_turns`, `error_max_budget_usd`, `error_during_execution`), `is_error`, `result`, `num_turns`, `total_cost_usd` and `usage`.
- Assistant messages arrive once per content block and repeat their API message's `usage`. Their output token count is a placeholder; the result's usage is the one that counts.
- Model and limits: `--model`, `--max-turns`, `--max-budget-usd` (2.1.217+) and `--append-system-prompt-file`.
- Tools: `--tools` limits the built-in set (`""` for none). Permission rules such as `Edit(**/test/**)` apply to every file-writing tool, and deny rules win.
- Unattended runs: `--permission-mode dontAsk` denies anything not allowed. `--permission-prompts none` (2.1.259+) stops it from retrying.
- Untrusted repositories: `--setting-sources user` keeps project settings and `.mcp.json` out.
- Credentials: `CLAUDE_CODE_OAUTH_TOKEN` takes a one-year token from `claude setup-token` (Pro, Max, Team or Enterprise plan), and `ANTHROPIC_API_KEY` takes an API key, which wins if both are set.
- Environment: `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` strips credentials from Bash and hook subprocesses, but requires bubblewrap; found in the first live run (2026-10-09), Claude Code exits with "bubblewrap is required for subprocess env scrubbing" when it is missing, so it is set only when `bwrap` is on the path. `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`, `DISABLE_TELEMETRY` and `DISABLE_ERROR_REPORTING` switch off everything but model calls. `HTTPS_PROXY` is honoured.
- Distribution: the native build is a self-contained binary per platform (`linux-x64`, `linux-arm64` and `-musl` variants), listed with SHA-256 checksums in `downloads.claude.ai/claude-code-releases/<version>/manifest.json`.

## Decision
- **An engine port in core.** `core.agent.ExternalAgent` does for one stage call what an `AgentLoop` does.
  - Its parameters: role, access, actor, instructions, brief and token budget.
  - It returns the same `AgentLoop.Outcome` (stop reason, final text, usage, turns), so stages post-process both engines the same way: diff checks, verdict parsing and escalation. A new stop reason, `FAILED`, covers a CLI that errors, times out or is missing.
  - `choose(task)` picks the engine per task: the loop, the external agent, or `Unavailable(reason)`. With `Unavailable`, every agent stage escalates to a human before doing anything.
  - `AgentStages` asks for the least access each call needs:
    - `NONE`: triage;
    - `READ_ONLY`: planner, spec critic and reviewer;
    - `TESTS_ONLY`: the test writer;
    - `FULL`: the coder.
- **Selection at runtime.** The AI model connector (ADR-0007) gains an `engine` field (`api` by default, or `claude-code`) and a secret `cliToken`. The token is a subscription token from `claude setup-token`, or an Anthropic API key, told apart by the `sk-ant-api` prefix.
  - The connector's `model` and per-role models become `--model`.
  - Prices are not required on this engine unless an API key is also set: Claude Code reports `total_cost_usd`, which becomes the call's cost.
  - Without a saved connector, `agentic.agent.cli.*` configures the same thing (enabled, token, model, binary, tools volume or directory, timeout, max turns, untrusted tasks).
- **The adapter, `adapter.out.cli.ClaudeCodeAgent`.** For each call it:
  - writes the instructions, the brief and, unless the access is `NONE`, the current diff to a scratch directory under `/tmp/.agentic-cli/` in the run's main container. The writes go through exec, never into the workspace. The diff is there because the sandbox has no git metadata, so it stands in for `show_diff`. A short note maps the prompts' tool names to Claude Code's.
  - runs `claude -p --output-format stream-json --verbose` with the brief on standard input, `--append-system-prompt-file`, `--model`, `--max-turns` (1 for triage), `--max-budget-usd` (what the run may still spend), `--setting-sources user`, a `--settings` file with `disableAllHooks`, an empty `--mcp-config` with `--strict-mcp-config`, `--permission-mode dontAsk` and `--permission-prompts none`;
  - gives each access its tools and rules:
    - `NONE`: `--tools ""`.
    - `READ_ONLY`: Read, Glob and Grep, plus reading its own scratch directory.
    - `TESTS_ONLY`: Read, Glob, Grep, Edit and Write, with edits allowed only by `Edit(<glob>)` rules from `TestPaths.globs()`. Every path those globs match is a test.
    - `FULL`: Read, Glob, Grep, Edit, Write and Bash, with `Edit(./**)` and `Bash`.
  - parses the stream into the events the loop records: `AGENT_MESSAGE`, `TOOL_CALLED` and `TOOL_RESULT`. Usage is counted once per message id and recorded with `recordSpend` as it arrives; at the end the result's usage and cost are taken where they are higher.
  - kills the CLI once streamed usage exceeds the token budget, and reports `BUDGET_EXHAUSTED`. `--max-budget-usd` stops it on cost from the inside.
  - removes its scratch files afterwards.
- **Streaming exec in the Sandbox port.** `Sandbox.execLines(runId, command, env, timeout)` delivers stdout lines as they arrive.
  - `env` applies to that command only.
  - A non-zero exit errors with `CommandFailedException`, which carries the stderr tail.
  - Cancelling kills the command. `DockerSandbox` records the exec's pid, and a second exec sends TERM and then KILL to its process group, falling back to the process alone. `DockerSandboxTest` checks that the command's background children die too. Closing the attach stream alone would leave the process running.
- **Triage needs the sandbox.** On the CLI engine, triage brings up the workspace first; the step is idempotent, so PREPARING_CONTEXT then finds it ready. A failed CLI triage escalates instead of defaulting to HIGH.
- **The test writer is held to tests.** After the test writer runs, the stage compares the diff, file by file, with the diff before it. If any changed file is not a test, all the writer's changes are put back to the base commit and the coder starts without tests-first. This applies to both engines; the loop's tools already make it impossible there.
  - The restore runs through the sandbox as its user: `RepositoryCheckout.baseFile` reads the base version on the host, then `writeFile` or `rm` runs in the container. Links the agent made cannot redirect a host-side write.
  - Only text files can be restored; anything else fails the stage, which escalates.
- **Installation.** Toolchain images vary, so the CLI is mounted read-only at `/opt/agentic-tools` in every sandbox container. The source is a Docker volume (`agentic.agent.cli.tools-volume`) or a host directory (`tools-dir`).
  - In Compose, the one-shot `cli-installer` service (profile `claude-code`, image `alpine:3.24.2`) downloads a pinned version (2.1.282), checks it against the manifest's SHA-256 and puts it in the `agentic-tools` volume.
  - We download the release directly instead of running `install.sh`. The installer script fetches the latest bootstrap binary, runs it, and writes into `$HOME`.

### Security
- **The credential.**
  - It lives only in the environment of the one exec that runs the CLI. It is never in the container's environment, in a command line or in a log.
  - Everything recorded passes through redaction: events, final text, failure messages and the stderr tail.
  - After an edit-capable call, a change that contains the token fails the call.
  - `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB` keeps the token out of tool subprocesses only where the image has bubblewrap. It is documented for API keys; whether it covers `CLAUDE_CODE_OAUTH_TOKEN` is not.
- **The coder can reach the token.** Its shell runs as the same user as the CLI, so it can read the token from the CLI's process. That is the inherent cost of running the harness next to the code, and why the next rule exists.
- **Untrusted tasks never run on the CLI.** That covers Jira, Slack, MCP and issues (`Trust.UNTRUSTED`).
  - They run on the API engine instead, if the connector also has an API key or a keyless provider.
  - Otherwise they escalate with a reason that says so.
  - `agentic.agent.cli.untrusted-tasks=true` overrides this; it defaults to false.
- **Network.** The CLI needs `api.anthropic.com`, which the egress allowlist now includes, with a comment. As a consequence, any code in a sandbox can reach that API with a key of its own. With `agentic.sandbox.network=none` the CLI engine is unavailable, and stages escalate with that reason.
- **The repository cannot configure the CLI.** It cannot add hooks, permission rules or MCP servers, because only user settings (empty) and our files are loaded. `CLAUDE.md` may still be read as guidance; the API engine passes it on as well.

## Consequences
- Positive:
  - A subscription or Claude Code setup a team already has can run the whole pipeline.
  - Gates, verification, review, budgets and the activity feed work as before.
  - The engine can be switched per installation from the UI without a restart.
- Negative:
  - Claude only.
  - The CLI is a moving dependency. We pin a version and use flags from 2.1.259 or later.
  - Its stream format is less granular than our own loop's events. We record no stuck detection, only the turn limit.
  - The repository memory's `remember` tool is not available on this engine. Recalled facts still reach the brief.
  - The cost of a call killed for its token budget is unknown; its tokens still count.
  - One binary serves every sandbox, so glibc and Alpine (musl) toolchain images cannot be mixed. A musl build needs `libgcc`, `libstdc++` and `ripgrep` in the image.
- Follow-ups:
  - Other CLIs behind the same port.
  - Resuming a session across fix rounds with `--resume`.
  - A network policy that lets only the CLI process, not its tools, reach the API.
