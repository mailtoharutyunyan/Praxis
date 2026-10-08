import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, type Api } from "../lib/api";
import { followEvents } from "../lib/sse";
import type { GateDecision, RiskLevel, Run, RunEvent } from "../lib/types";
import { TERMINAL } from "../lib/types";
import { ApprovalPanel } from "../components/ApprovalPanel";
import { Markdown } from "../components/Markdown";
import { Pipeline } from "../components/Pipeline";
import { RevisionPanel } from "../components/RevisionPanel";
import { StateBadge } from "../components/StateBadge";
import { Timeline } from "../components/Timeline";

export function RunPage(props: {
  api: Api;
  id: string;
  token: () => Promise<string | null>;
  roles: string[];
}) {
  const { api, id, token, roles } = props;
  const [run, setRun] = useState<Run | null>(null);
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [verbose, setVerbose] = useState(false);

  // Stale responses are ignored: requests for a previously shown run are aborted, and of overlapping
  // refreshes (bursts of events, polling after actions) an older response never replaces a newer one.
  const scope = useRef({ id, controller: new AbortController() });
  const issued = useRef(0);
  const applied = useRef(0);

  const refresh = useCallback(async () => {
    const { id: current, controller: { signal } } = scope.current;
    if (current !== id) return;
    const ticket = ++issued.current;
    try {
      const fresh = await api.getRun(id, signal);
      if (signal.aborted || ticket < applied.current) return;
      applied.current = ticket;
      setRun(fresh);
    } catch (e) {
      if (signal.aborted || ticket < applied.current) return;
      setError(e instanceof Error ? e.message : "Could not load the run.");
    }
  }, [api, id]);

  useEffect(() => {
    const controller = new AbortController();
    if (scope.current.id !== id) {
      setRun(null);
      setError(null);
    }
    scope.current = { id, controller };
    setEvents([]);
    void refresh();
    const stop = followEvents({
      url: api.eventStreamUrl(id),
      token,
      onEvent: (event) => {
        setEvents((current) => current.some((e) => e.seq === event.seq) ? current : [...current, event]);
        if (event.type === "STATE_CHANGED" || event.type === "TRIAGED" || event.type === "RISK_RAISED"
          || event.type === "USAGE_RECORDED") void refresh();
      },
      onEnd: () => void refresh(),
    });
    return () => {
      controller.abort();
      stop();
    };
  }, [api, id, token, refresh]);

  const act = async (action: () => Promise<Run>) => {
    try {
      setError(null);
      await action();
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The action failed.");
    }
  };

  if (!run) return error ? <div className="alert error" role="alert">{error}</div> : <p className="muted">Loading…</p>;

  const isApprover = roles.includes("approver");
  const isOperator = roles.includes("operator");
  const live = !TERMINAL.includes(run.state);
  // One pull request per repository: the primary one and any companions.
  const prs = [...new Map(events.filter((e) => e.type === "ARTIFACT_PRODUCED" && e.payload.kind === "pull-request")
    .map((e) => [String(e.payload.repository ?? "primary"), e])).values()];

  // Rejects on failure, so the approval panel keeps the comment and shows the error.
  const decide = async (decision: GateDecision, comment: string) => {
    setError(null);
    await api.decide(run.id, run.pendingGate!, decision, comment);
    await refresh();
  };
  // Rejects on failure, so the panel keeps the text and shows the error.
  const revise = async (text: string, location: string) => {
    setError(null);
    await api.requestRevision(run.id, text, location);
    await refresh();
  };
  const raise = () => {
    const risk = window.prompt("Raise risk to (MEDIUM or HIGH)", "HIGH")?.toUpperCase() as RiskLevel | undefined;
    const reason = risk ? window.prompt("Why?") : null;
    if (risk && reason) void act(() => api.raiseRisk(run.id, risk, reason));
  };

  return (
    <div className="stack">
      <div className="card stack" style={{ gap: 10 }}>
        <div className="row">
          <h1 style={{ margin: 0, fontSize: 20 }}>
            {run.task.externalRef ? `${run.task.externalRef} · ` : ""}{run.task.title}
          </h1>
          <StateBadge state={run.state} />
          {run.task.trust === "UNTRUSTED" && <span className="badge warn" title="Text from an external system">untrusted source</span>}
        </div>
        <Pipeline run={run} />
        {prs.map((pr) => (
          <p key={String(pr.payload.url)} style={{ margin: 0 }}>
            Pull request{pr.payload.alias ? ` (${String(pr.payload.alias)})` : ""}:{" "}
            <a href={String(pr.payload.url)} target="_blank" rel="noreferrer">{String(pr.payload.url)}</a>
          </p>
        ))}
        {run.state === "NEEDS_HUMAN" && (
          <div className="alert attention">This run needs a human. Check the latest error below, fix the cause (e.g. configuration
            or repository access), then resume — it continues at {run.resumeState?.toLowerCase().replace(/_/g, " ")}.</div>
        )}
        <div className="row">
          {isOperator && run.state === "NEEDS_HUMAN" && <button onClick={() => void act(() => api.resume(run.id))}>Resume</button>}
          {isApprover && live && run.risk && run.risk !== "HIGH" && run.state !== "PUBLISHING" && run.state !== "PR_OPEN"
            && <button onClick={raise}>Raise risk</button>}
          <span className="spacer" />
          {isOperator && live && (
            <button className="danger" onClick={() => {
              const reason = window.prompt("Cancel this run? Reason:");
              if (reason !== null) void act(() => api.cancel(run.id, reason));
            }}>Cancel run</button>
          )}
        </div>
      </div>

      {error && <div className="alert error" role="alert">{error}</div>}

      <div className="grid-2">
        <div className="stack">
          {run.state === "AWAITING_APPROVAL" && run.pendingGate && (
            <ApprovalPanel run={run} events={events} canApprove={isApprover} onDecide={decide} />
          )}
          {run.state === "PR_OPEN" && isOperator && <RevisionPanel onRequest={revise} />}
          <section className="card">
            <div className="row" style={{ marginBottom: 8 }}>
              <h2 style={{ margin: 0, fontSize: 16 }}>Activity</h2>
              {live && <span className="muted small">live</span>}
              <span className="spacer" />
              <label className="row small" style={{ display: "flex" }}>
                <input type="checkbox" style={{ width: "auto" }} checked={verbose} onChange={(e) => setVerbose(e.target.checked)} />
                show tool calls
              </label>
            </div>
            <Timeline events={events} verbose={verbose} />
          </section>
        </div>
        <aside className="stack">
          <section className="card">
            <dl className="kv">
              <dt>Repository</dt><dd>{run.task.cloneUrl}</dd>
              <dt>Base branch</dt><dd>{run.task.baseBranch ?? "default"}</dd>
              <dt>Risk</dt><dd>{run.risk ?? "not triaged yet"}</dd>
              <dt>Gates</dt><dd>{run.gates.join(", ") || "—"}</dd>
              <dt>Fix loops</dt><dd>{run.fixIterations}</dd>
              <dt>Review loops</dt><dd>{run.reviewLoops}</dd>
              <dt>Tokens</dt><dd>{(run.usage.inputTokens + run.usage.outputTokens + run.usage.cacheReadTokens + run.usage.cacheWriteTokens).toLocaleString()}</dd>
              <dt>Cost</dt><dd>${run.usage.costUsd.toFixed(4)}</dd>
              <dt>Requested by</dt><dd>{run.task.requestedBy}</dd>
            </dl>
          </section>
          <section className="card">
            <h2 style={{ marginTop: 0, fontSize: 16 }}>Task</h2>
            <Markdown source={run.task.description} />
          </section>
        </aside>
      </div>
    </div>
  );
}
