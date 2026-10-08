import type { RunState } from "../lib/types";

const TONE: Record<RunState, string> = {
  RECEIVED: "neutral", TRIAGING: "info", PREPARING_CONTEXT: "info", SPECIFYING: "info", IMPLEMENTING: "info",
  VERIFYING: "info", REVIEWING: "info", PUBLISHING: "info", AWAITING_APPROVAL: "warn", NEEDS_HUMAN: "warn",
  PR_OPEN: "ok", DONE: "ok", FAILED: "danger", CANCELLED: "neutral",
};

export function label(state: RunState): string {
  return state.toLowerCase().replace(/_/g, " ");
}

const LIVE = new Set<RunState>(["TRIAGING", "PREPARING_CONTEXT", "SPECIFYING", "IMPLEMENTING", "VERIFYING", "REVIEWING", "PUBLISHING"]);

export function StateBadge({ state }: { state: RunState }) {
  return <span className={`badge ${TONE[state]} ${LIVE.has(state) ? "live" : ""}`}>{label(state)}</span>;
}
