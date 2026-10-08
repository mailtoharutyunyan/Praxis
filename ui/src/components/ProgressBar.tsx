import type { RunProgress } from "../lib/types";

/** Where a run stands: phase, step, a bar with the percentage, and (optionally) what it is doing right now. */
export function ProgressBar(props: { progress: RunProgress; compact?: boolean; large?: boolean }) {
  const { progress, compact, large } = props;
  const tone = progress.finished ? (progress.percent === 100 ? "ok" : "muted") : progress.waiting ? "warn" : "active";
  return (
    <div className={`progress ${tone} ${large ? "large" : ""}`} aria-label="progress">
      <div className="progress-head">
        <span className={large ? "" : "small"} style={large ? { fontWeight: 650 } : undefined}>{progress.phase}</span>
        <span className="small muted">
          {compact ? "" : `step ${progress.step} of ${progress.steps} · `}<b>{progress.percent}%</b>
        </span>
      </div>
      <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100}
        aria-valuenow={progress.percent} aria-valuetext={`${progress.percent}% · ${progress.phase}`}>
        <div className="progress-fill" style={{ width: `${progress.percent}%` }} />
      </div>
      {!compact && <div className="small muted progress-activity" title={progress.activity}>{progress.activity}</div>}
    </div>
  );
}
