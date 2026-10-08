import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, type Api } from "../lib/api";
import { followEvents } from "../lib/sse";
import type { GateDecision, RiskLevel, Run, RunEvent } from "../lib/types";
import { TERMINAL } from "../lib/types";
import { ApprovalPanel } from "../components/ApprovalPanel";
import { Markdown } from "../components/Markdown";
import { Pipeline } from "../components/Pipeline";
import { ProgressBar } from "../components/ProgressBar";
import { RevisionPanel } from "../components/RevisionPanel";
import { StateBadge } from "../components/StateBadge";
import { Timeline } from "../components/Timeline";
import { Icon } from "../components/Icon";

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
  const progressTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(progressTimer.current), []);
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
        // Progress moves with every agent step; refresh at most about once a second while it works.
        else if (progressTimer.current === undefined) {
          progressTimer.current = window.setTimeout(() => { progressTimer.current = undefined; void refresh(); }, 1000);
        }
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

  const tokens = run.usage.inputTokens + run.usage.outputTokens + run.usage.cacheReadTokens + run.usage.cacheWriteTokens;
  const repo = run.task.cloneUrl.replace(/^https:\/\/[^/]+\//, "").replace(/\.git$/, "");
  return (
    <div className="stack" style={{ gap: 20 }}>
      <div>
        <div className="crumbs"><a href="#/">Runs</a><span>/</span><span className="mono">{run.id.slice(0, 8)}</span></div>
        <div className="row" style={{ alignItems: "flex-start", gap: 16 }}>
          <div style={{ flex: "1 1 320px", minWidth: 0 }} className="run-head">
            <h1>{run.task.externalRef && <span className="ref" style={{ marginRight: 8, verticalAlign: 4 }}>{run.task.externalRef}</span>}{run.task.title}</h1>
          </div>
          <div className="row">
            {isOperator && run.state === "NEEDS_HUMAN" && (
              <button className="primary" onClick={() => void act(() => api.resume(run.id))}><Icon name="resume" />Resume</button>
            )}
            {isApprover && live && run.risk && run.risk !== "HIGH" && run.state !== "PUBLISHING" && run.state !== "PR_OPEN"
              && <button onClick={raise}><Icon name="risk" />Raise risk</button>}
            {isOperator && live && (
              <button className="danger" onClick={() => {
                const reason = window.prompt("Cancel this run? Reason:");
                if (reason !== null) void act(() => api.cancel(run.id, reason));
              }}><Icon name="stop" />Cancel run</button>
            )}
          </div>
        </div>
        <div className="meta" style={{ marginTop: 10 }}>
          <StateBadge state={run.state} />
          {run.risk && <span className={`risk ${run.risk}`}>{run.risk} risk</span>}
          {run.task.trust === "UNTRUSTED" && <span className="badge warn plain" title="Text from an external system"><Icon name="shield" />untrusted source</span>}
          <span><Icon name="repo" />{repo}</span>
          <span><Icon name="branch" />{run.task.baseBranch ?? "default branch"}</span>
          <span><Icon name="user" />{run.task.requestedBy}</span>
          <span><Icon name="clock" />{new Date(run.createdAt).toLocaleString()}</span>
        </div>
      </div>

      <section className="card stack" style={{ gap: 20 }} aria-label="Run progress">
        {run.progress && <ProgressBar progress={run.progress} large />}
        <Pipeline run={run} />
        {prs.map((pr) => (
          <div key={String(pr.payload.url)} className="callout ok">
            <Icon name="pr" />
            <div>
              <b>Pull request{pr.payload.alias ? ` (${String(pr.payload.alias)})` : ""}</b>
              <div><a href={String(pr.payload.url)} target="_blank" rel="noreferrer">{String(pr.payload.url)}</a></div>
            </div>
          </div>
        ))}
        {run.state === "NEEDS_HUMAN" && (
          <div className="callout warn">
            <Icon name="alert" />
            <div>This run needs a human. Check the latest error in the activity, fix the cause (e.g. configuration
              or repository access), then resume. It continues at {run.resumeState?.toLowerCase().replace(/_/g, " ")}.</div>
          </div>
        )}
      </section>

      {error && <div className="alert error" role="alert">{error}</div>}

      <div className="grid-2">
        <div className="stack">
          {run.state === "AWAITING_APPROVAL" && run.pendingGate && (
            <ApprovalPanel run={run} events={events} canApprove={isApprover} onDecide={decide} />
          )}
          {run.state === "PR_OPEN" && isOperator && <RevisionPanel onRequest={revise} />}
          <section className="card">
            <div className="card-title">
              <Icon name="runs" />Activity
              {live && <span className="badge info live">live</span>}
              <span className="spacer" />
              <label className="row small" style={{ display: "flex", fontWeight: 500 }}>
                <input type="checkbox" checked={verbose} onChange={(e) => setVerbose(e.target.checked)} />
                show tool calls
              </label>
            </div>
            <Timeline events={events} verbose={verbose} />
          </section>
        </div>
        <aside className="stack">
          <section className="card">
            <h2 className="card-title">Usage</h2>
            <div className="stats">
              <div className="stat"><b>${run.usage.costUsd.toFixed(run.usage.costUsd < 1 ? 4 : 2)}</b><span>cost</span></div>
              <div className="stat"><b>{tokens.toLocaleString()}</b><span>tokens</span></div>
              <div className="stat"><b>{run.fixIterations}</b><span>fix loops</span></div>
              <div className="stat"><b>{run.reviewLoops}</b><span>review loops</span></div>
            </div>
          </section>
          <section className="card">
            <h2 className="card-title">Details</h2>
            <dl className="kv">
              <dt>Repository</dt><dd className="mono">{run.task.cloneUrl}</dd>
              <dt>Base branch</dt><dd>{run.task.baseBranch ?? "default"}</dd>
              <dt>Risk</dt><dd>{run.risk ?? "not triaged yet"}</dd>
              <dt>Gates</dt><dd>{run.gates.join(", ") || "—"}</dd>
              <dt>Source</dt><dd>{run.task.origin.toLowerCase().replace(/_/g, " ")}</dd>
              <dt>Requested by</dt><dd>{run.task.requestedBy}</dd>
            </dl>
          </section>
          <section className="card">
            <h2 className="card-title">Task</h2>
            <Markdown source={run.task.description} />
          </section>
        </aside>
      </div>
    </div>
  );
}
