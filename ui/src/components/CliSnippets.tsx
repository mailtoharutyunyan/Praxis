import { useState } from "react";
import { Icon } from "./Icon";

type Cli = "claude" | "codex" | "gemini";

/** How to connect each AI CLI to this app's MCP server; {@code token} fills the snippets when known. */
/**
 * How to connect each AI CLI to this app's MCP server (Streamable HTTP, bearer token), per each CLI's docs as of
 * 2026-10: Claude Code {@code claude mcp add}, Codex {@code codex mcp add --bearer-token-env-var}, Gemini CLI
 * {@code gemini mcp add --transport http}. {@code token} fills the snippets when known.
 */
export function cliSnippet(cli: Cli, mcpUrl: string, token: string): string {
  switch (cli) {
    case "claude":
      return `claude mcp add --transport http --scope user agentic-sdlc ${mcpUrl} \\\n  --header "Authorization: Bearer ${token}"`;
    case "codex":
      return `export AGENTIC_SDLC_TOKEN=${token}   # e.g. in ~/.zshrc\ncodex mcp add agentic-sdlc --url ${mcpUrl} --bearer-token-env-var AGENTIC_SDLC_TOKEN`;
    case "gemini":
      return `gemini mcp add --scope user --transport http \\\n  --header "Authorization: Bearer ${token}" agentic-sdlc ${mcpUrl}`;
  }
}

const LABELS: Record<Cli, string> = { claude: "Claude Code", codex: "Codex CLI", gemini: "Gemini CLI" };

export function CopyButton({ text, label = "Copy" }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button type="button" onClick={() => {
      void navigator.clipboard?.writeText(text).then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      });
    }}>
      <Icon name={copied ? "check" : "file"} />{copied ? "Copied" : label}
    </button>
  );
}

/** Snippets that connect Claude Code, Codex CLI or Gemini CLI to this app over MCP. */
export function CliSnippets({ token }: { token?: string }) {
  const [cli, setCli] = useState<Cli>("claude");
  const mcpUrl = `${window.location.origin}/mcp`;
  const snippet = cliSnippet(cli, mcpUrl, token ?? "<your API token>");
  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="tabs" role="tablist" aria-label="AI CLI">
        {(Object.keys(LABELS) as Cli[]).map((key) => (
          <button key={key} role="tab" aria-selected={cli === key} className={`tab ${cli === key ? "active" : ""}`}
            onClick={() => setCli(key)}>{LABELS[key]}</button>
        ))}
      </div>
      <pre aria-label={`${LABELS[cli]} setup`} style={{ margin: 0, whiteSpace: "pre-wrap" }}>{snippet}</pre>
      <div className="row">
        <CopyButton text={snippet} />
        <span className="muted small">
          Then ask, for example: "Use agentic-sdlc to add a /health endpoint to https://github.com/acme/shop.git and tell me when it waits for approval."
        </span>
      </div>
    </div>
  );
}
