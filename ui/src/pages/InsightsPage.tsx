import { useEffect, useState } from "react";
import { ApiError, type Api } from "../lib/api";
import type { DailyCount, Insights } from "../lib/types";
import { Icon } from "../components/Icon";

const RANGES = [7, 30, 90] as const;
const DASH = "—";

function percent(rate: number | null): string {
  return rate === null ? DASH : `${Math.round(rate * 1000) / 10}%`;
}

function duration(minutes: number | null): string {
  if (minutes === null) return DASH;
  return minutes < 60 ? `${Math.round(minutes * 10) / 10} min` : `${(minutes / 60).toFixed(1)} h`;
}

/** Runs per day as plain SVG bars, scaled to the busiest day. */
function RunsPerDayChart({ days }: { days: DailyCount[] }) {
  const busiest = Math.max(0, ...days.map((d) => d.count));
  const max = Math.max(1, busiest);
  const slot = 10;
  const height = 100;
  const total = days.reduce((sum, d) => sum + d.count, 0);
  return (
    <div className="bar-chart">
      <svg viewBox={`0 0 ${days.length * slot} ${height}`} preserveAspectRatio="none" role="img"
        aria-label={`Runs per day: ${total} runs over ${days.length} days`}>
        {days.map((d, i) => {
          const h = (d.count / max) * height;
          return (
            <rect key={d.date} x={i * slot + 1} y={height - h} width={slot - 2} height={h}>
              <title>{`${d.date}: ${d.count} runs`}</title>
            </rect>
          );
        })}
      </svg>
      <div className="bar-chart-axis">
        <span>{days[0]?.date}</span>
        <span>busiest day: {busiest} runs</span>
        <span>{days[days.length - 1]?.date}</span>
      </div>
    </div>
  );
}

/** How the agents are doing: outcomes, time to pull request, spend and activity over the last days. */
export function InsightsPage(props: { api: Api }) {
  const { api } = props;
  const [days, setDays] = useState<number>(30);
  const [insights, setInsights] = useState<Insights | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    api.insights(days, controller.signal).then(
      (next) => { if (!controller.signal.aborted) setInsights(next); },
      (e: unknown) => {
        if (!controller.signal.aborted) setError(e instanceof ApiError ? e.message : "Could not load the insights.");
      },
    );
    return () => controller.abort();
  }, [api, days]);

  const { finished, minutesToPullRequest: toPr } = insights ?? { finished: null, minutesToPullRequest: null };
  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Insights</h1>
          <p>How the agents are doing: outcomes, time to pull request and spend for the runs started in the period (UTC).</p>
        </div>
        <span className="spacer" />
        <div className="chips" role="tablist" aria-label="period">
          {RANGES.map((n) => (
            <button key={n} role="tab" aria-selected={days === n} className={`chip ${days === n ? "active" : ""}`}
              onClick={() => setDays(n)}>{n} days</button>
          ))}
        </div>
      </div>
      <div className="stack">
        {error && <div className="alert error" role="alert">{error}</div>}
        {insights === null || finished === null || toPr === null ? (!error && <p className="muted">Loading…</p>) : (
          <>
            <div className="stats wide">
              <div className="stat"><b>{insights.runsStarted.toLocaleString()}</b><span>runs started</span></div>
              <div className="stat"><b>{percent(insights.successRate)}</b><span>success rate</span></div>
              <div className="stat">
                <b>{finished.DONE} / {finished.FAILED} / {finished.CANCELLED}</b><span>done / failed / cancelled</span>
              </div>
              <div className="stat"><b>{duration(toPr.median)}</b><span>median time to PR ({toPr.count} PRs)</span></div>
              <div className="stat"><b>{duration(toPr.p90)}</b><span>p90 time to PR</span></div>
              <div className="stat"><b>${insights.costUsd.toFixed(2)}</b><span>cost (USD)</span></div>
              <div className="stat"><b>{insights.totalTokens.toLocaleString()}</b><span>tokens</span></div>
            </div>
            <section className="card stack">
              <h2 className="card-title" style={{ margin: 0 }}><Icon name="chart" />Runs per day</h2>
              <RunsPerDayChart days={insights.runsPerDay} />
            </section>
          </>
        )}
      </div>
    </div>
  );
}
