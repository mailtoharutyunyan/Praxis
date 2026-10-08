import type { RunEvent } from "../lib/types";

function time(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
}

function text(value: unknown): string {
  return value === null || value === undefined ? "" : String(value);
}

function Body({ event }: { event: RunEvent }) {
  const p = event.payload;
  switch (event.type) {
    case "RUN_CREATED":
      return <span>Run created from <b>{text(p.origin)}</b>{p.externalRef ? ` ${text(p.externalRef)}` : ""}</span>;
    case "STATE_CHANGED":
      return <span>{text(p.from).toLowerCase().replace(/_/g, " ")} → <b>{text(p.to).toLowerCase().replace(/_/g, " ")}</b></span>;
    case "TRIAGED":
      return <span>Triaged as <b>{text(p.risk)}</b> — {text(p.rationale)}</span>;
    case "GATE_OPENED":
      return <span>Waiting for approval at the <b>{text(p.gate)}</b> gate</span>;
    case "GATE_DECIDED":
      return <span><b>{event.actor}</b> {text(p.decision).toLowerCase().replace(/_/g, " ")} at {text(p.gate)}{p.comment ? `: “${text(p.comment)}”` : ""}</span>;
    case "REVISION_REQUESTED":
      return <span>Changes requested via <b>{text(p.source)}</b> by {text(p.author)}{p.location ? ` on ${text(p.location)}` : ""}: “{text(p.text)}”</span>;
    case "RISK_RAISED":
      return <span>Risk raised to <b>{text(p.to)}</b> by {event.actor}: {text(p.reason)}</span>;
    case "AGENT_MESSAGE":
      return <details><summary>{event.actor} says…</summary><pre>{text(p.text ?? p.message)}</pre></details>;
    case "TOOL_CALLED":
      return <details><summary><code>{text(p.tool)}</code></summary><pre>{text(p.arguments)}</pre></details>;
    case "TOOL_RESULT":
      return <details><summary>{p.error ? "⚠ " : ""}result of <code>{text(p.tool)}</code></summary><pre>{text(p.output)}</pre></details>;
    case "COMMAND_OUTPUT":
      return <details><summary><code>{text(p.command)}</code> → {p.timedOut ? "timed out" : `exit ${text(p.exitCode)}`} ({text(p.durationMs)} ms)</summary><pre>{text(p.output)}</pre></details>;
    case "ARTIFACT_PRODUCED":
      return p.kind === "pull-request"
        ? <span>Pull request opened: <a href={text(p.url)} target="_blank" rel="noreferrer">{text(p.url)}</a></span>
        : <span>Produced <b>{text(p.kind)}</b>{p.verdict ? ` (${text(p.verdict)})` : ""}</span>;
    case "USAGE_RECORDED":
      return <span className="muted">Model usage: {text(p.inputTokens)} in / {text(p.outputTokens)} out, run total ${(Number(p.runCostMicroUsd ?? 0) / 1e6).toFixed(4)}</span>;
    case "STAGE_COMPLETED":
      return <span className="muted">{text(p.stage).toLowerCase().replace(/_/g, " ")} finished: {text(p.outcome)}</span>;
    case "ERROR":
      return <span className="badge danger" style={{ whiteSpace: "normal" }}>{text(p.kind)}: {text(p.reason)}</span>;
    default:
      return <span>{event.type}</span>;
  }
}

export function Timeline({ events, verbose }: { events: RunEvent[]; verbose: boolean }) {
  const quiet = new Set(["TOOL_CALLED", "TOOL_RESULT", "USAGE_RECORDED", "STAGE_COMPLETED"]);
  const shown = verbose ? events : events.filter((e) => !quiet.has(e.type));
  return (
    <div className="timeline" aria-label="timeline">
      {shown.map((event) => (
        <div className="event" key={event.seq}>
          <time dateTime={event.occurredAt}>{time(event.occurredAt)}</time>
          <div><Body event={event} /> {event.type !== "GATE_DECIDED" && event.actor !== "system" && <span className="actor">· {event.actor}</span>}</div>
        </div>
      ))}
      {shown.length === 0 && <p className="muted">No events yet.</p>}
    </div>
  );
}
