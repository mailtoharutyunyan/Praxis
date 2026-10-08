import { PIPELINE, type Run, type RunState } from "../lib/types";
import { label } from "./StateBadge";

const GATE_STAGE: Record<string, RunState> = { SPEC: "SPECIFYING", IMPLEMENTATION: "VERIFYING", PUBLISH: "REVIEWING" };

/** Pipeline progress; a waiting run is shown at the stage its gate follows. */
export function Pipeline({ run }: { run: Run }) {
  const current: RunState =
    run.state === "AWAITING_APPROVAL" && run.pendingGate ? GATE_STAGE[run.pendingGate]
      : run.state === "NEEDS_HUMAN" && run.resumeState ? run.resumeState
        : run.state;
  const index = PIPELINE.indexOf(current);
  return (
    <div className="pipeline" aria-label="pipeline">
      {PIPELINE.map((stage, i) => (
        <span key={stage} className={`step ${i < index || run.state === "DONE" ? "done" : i === index ? "current" : ""}`}>
          {label(stage)}
        </span>
      ))}
    </div>
  );
}
