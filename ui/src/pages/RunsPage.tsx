import { useEffect, useRef, useState } from "react";
import type { Api } from "../lib/api";
import type { Run, RunState } from "../lib/types";
import { NewTaskDialog } from "../components/NewTaskDialog";
import { ProgressBar } from "../components/ProgressBar";
import { StateBadge } from "../components/StateBadge";

const FILTERS: { key: string; label: string; states: RunState[] }[] = [
  { key: "attention", label: "Needs me", states: ["AWAITING_APPROVAL", "NEEDS_HUMAN"] },
  { key: "active", label: "In progress", states: ["RECEIVED", "TRIAGING", "PREPARING_CONTEXT", "SPECIFYING", "IMPLEMENTING", "VERIFYING", "REVIEWING", "PUBLISHING"] },
  { key: "open", label: "PR open", states: ["PR_OPEN"] },
  { key: "finished", label: "Finished", states: ["DONE", "FAILED", "CANCELLED"] },
  { key: "all", label: "All", states: [] },
];

function ago(iso: string): string {
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  return hours < 48 ? `${hours} h ago` : `${Math.round(hours / 24)} d ago`;
}

function repoName(url: string): string {
  return url.replace(/^https:\/\/[^/]+\//, "").replace(/\.git$/, "");
}

export function RunsPage({ api, canSubmit, navigate }: { api: Api; canSubmit: boolean; navigate: (path: string) => void }) {
  const [filter, setFilter] = useState("attention");
  const [runs, setRuns] = useState<Run[]>([]);
  const [error, setError] = useState<string | null>(null);

  const issued = useRef(0);
  const applied = useRef(0);

  useEffect(() => {
    // Aborted when the filter changes or the page unmounts, so a slow response never shows another filter's runs.
    const controller = new AbortController();
    const states = FILTERS.find((f) => f.key === filter)!.states;
    const load = async () => {
      // Polls can overlap: an older response never replaces a newer one.
      const ticket = ++issued.current;
      try {
        const page = await api.listRuns(states, 100, undefined, controller.signal);
        if (controller.signal.aborted || ticket < applied.current) return;
        applied.current = ticket;
        setRuns(page.items);
        setError(null);
      } catch (e) {
        if (controller.signal.aborted || ticket < applied.current) return;
        setError(e instanceof Error ? e.message : "Could not load runs.");
      }
    };
    void load();
    const timer = setInterval(() => void load(), 5000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [api, filter]);

  return (
    <div className="stack">
      <div className="row">
        <div className="chips" role="tablist" aria-label="filter">
          {FILTERS.map((f) => (
            <button key={f.key} role="tab" aria-selected={filter === f.key} className={`chip ${filter === f.key ? "active" : ""}`}
              onClick={() => setFilter(f.key)}>{f.label}</button>
          ))}
        </div>
        <span className="spacer" />
        {canSubmit && <NewTaskDialog api={api} onCreated={(run) => navigate(`/runs/${run.id}`)} />}
      </div>
      {error && <div className="alert error" role="alert">{error}</div>}
      <div className="card" style={{ padding: 0, overflowX: "auto" }}>
        <table>
          <thead>
            <tr><th>Task</th><th>Repository</th><th>State</th><th>Progress</th><th>Risk</th><th>Cost</th><th>Created</th></tr>
          </thead>
          <tbody>
            {runs.map((run) => (
              <tr key={run.id} className="clickable" onClick={() => navigate(`/runs/${run.id}`)}>
                <td>
                  <div style={{ fontWeight: 600 }}>{run.task.externalRef ? `${run.task.externalRef} · ` : ""}{run.task.title}</div>
                  <div className="muted small">{run.task.requestedBy}</div>
                </td>
                <td className="small">{repoName(run.task.cloneUrl)}</td>
                <td><StateBadge state={run.state} />{run.pendingGate && <div className="muted small">{run.pendingGate} gate</div>}</td>
                <td>{run.progress && <ProgressBar progress={run.progress} compact />}</td>
                <td className="small">{run.risk ?? "—"}</td>
                <td className="small">${run.usage.costUsd.toFixed(2)}</td>
                <td className="small muted">{ago(run.createdAt)}</td>
              </tr>
            ))}
            {runs.length === 0 && (
              <tr><td colSpan={6} className="muted" style={{ textAlign: "center", padding: 32 }}>Nothing here.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
