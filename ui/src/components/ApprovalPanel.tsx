import { useMemo, useState } from "react";
import { ApiError } from "../lib/api";
import type { GateDecision, Run, RunEvent } from "../lib/types";
import { DiffView } from "./DiffView";
import { Markdown } from "./Markdown";

type Tab = "spec" | "diff" | "review";

function latest(events: RunEvent[], kind: string): RunEvent | undefined {
  return [...events].reverse().find((e) => e.type === "ARTIFACT_PRODUCED" && e.payload.kind === kind);
}

const WHAT: Record<string, string> = {
  SPEC: "Approve the specification before any code is written.",
  IMPLEMENTATION: "Approve the verified changes before the independent review.",
  PUBLISH: "Approve committing, pushing and opening a pull request. Merging stays with your reviewers.",
};

/** What the approver is approving (spec, diff, review) and the decision buttons. */
export function ApprovalPanel(props: {
  run: Run;
  events: RunEvent[];
  canApprove: boolean;
  onDecide: (decision: GateDecision, comment: string) => Promise<void>;
}) {
  const { run, events, canApprove, onDecide } = props;
  const spec = latest(events, "spec");
  const diff = latest(events, "diff");
  const review = latest(events, "review");
  const initial: Tab = run.pendingGate === "SPEC" ? "spec" : "diff";
  const [tab, setTab] = useState<Tab>(initial);
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const tabs = useMemo(() => ([
    ["spec", "Specification", spec], ["diff", "Changes", diff], ["review", "Review", review],
  ] as const).filter(([, , artifact]) => artifact !== undefined), [spec, diff, review]);

  const decide = async (decision: GateDecision) => {
    setBusy(true);
    setError(null);
    try {
      await onDecide(decision, comment);
      setComment("");
    } catch (e) {
      // Keep the comment, so the approver can retry without retyping it.
      setError(e instanceof ApiError ? e.message : "The decision could not be sent. Try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card stack" aria-label="approval">
      <div>
        <h2 style={{ margin: 0, fontSize: 16 }}>Waiting for approval: {run.pendingGate} gate</h2>
        <p className="muted small" style={{ margin: "4px 0 0" }}>{WHAT[run.pendingGate ?? ""]}</p>
      </div>
      {tabs.length > 0 && (
        <div>
          <div className="tabs" role="tablist">
            {tabs.map(([key, title]) => (
              <button key={key} role="tab" aria-selected={tab === key} className={`tab ${tab === key ? "active" : ""}`}
                onClick={() => setTab(key)}>{title}</button>
            ))}
          </div>
          {tab === "spec" && spec && <Markdown source={String(spec.payload.content ?? "")} />}
          {tab === "diff" && diff && <DiffView diff={String(diff.payload.content ?? "")} />}
          {tab === "review" && review && (
            <>
              <p><span className={`badge ${review.payload.verdict === "APPROVE" ? "ok" : "warn"}`}>{String(review.payload.verdict)}</span></p>
              <Markdown source={String(review.payload.content ?? "")} />
            </>
          )}
        </div>
      )}
      {canApprove ? (
        <div className="stack" style={{ gap: 8 }}>
          <label>Comment (sent to the agent when you request changes)
            <textarea value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Optional" />
          </label>
          {error && <div className="alert error" role="alert">{error}</div>}
          <div className="row">
            <button className="primary" disabled={busy} onClick={() => decide("APPROVE")}>Approve</button>
            <button disabled={busy || !comment.trim()} onClick={() => decide("REQUEST_CHANGES")}
              title={comment.trim() ? "" : "Write what should change"}>Request changes</button>
            <span className="spacer" />
            <button className="danger" disabled={busy} onClick={() => decide("REJECT")}>Reject</button>
          </div>
        </div>
      ) : (
        <p className="muted small">You need the approver role to decide this gate.</p>
      )}
    </section>
  );
}
