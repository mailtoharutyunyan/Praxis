import { PIPELINE, type Gate, type Run, type RunState } from "../lib/types";
import { Icon } from "./Icon";
import { label } from "./StateBadge";

const GATE_STAGE: Record<string, RunState> = { SPEC: "SPECIFYING", IMPLEMENTATION: "VERIFYING", PUBLISH: "REVIEWING" };
/** The stage each gate leads into: its marker sits on the connector before that stage. */
const GATE_BEFORE: Partial<Record<RunState, Gate>> = { IMPLEMENTING: "SPEC", REVIEWING: "IMPLEMENTATION", PUBLISHING: "PUBLISH" };

/** Stage tracker; a waiting run is shown at the stage its gate follows, and this run's gates are marked. */
export function Pipeline({ run }: { run: Run }) {
  const current: RunState =
    run.state === "AWAITING_APPROVAL" && run.pendingGate ? GATE_STAGE[run.pendingGate]
      : run.state === "NEEDS_HUMAN" && run.resumeState ? run.resumeState
        : run.state;
  const index = PIPELINE.indexOf(current);
  const waiting = run.state === "AWAITING_APPROVAL" || run.state === "NEEDS_HUMAN";
  const stopped = run.state === "FAILED" || run.state === "CANCELLED";
  return (
    <div className="pipeline" aria-label="pipeline">
      {PIPELINE.map((stage, i) => {
        const done = i < index || run.state === "DONE";
        const isCurrent = i === index && run.state !== "DONE";
        const gate = GATE_BEFORE[stage];
        const hasGate = gate !== undefined && run.gates.includes(gate);
        return (
          <div key={stage} className={`step ${done ? "done" : ""} ${isCurrent ? "current" : ""} ${isCurrent && waiting ? "waiting" : ""} ${isCurrent && stopped ? "stopped" : ""}`}
            aria-current={isCurrent ? "step" : undefined}>
            {hasGate && <span className={`gate ${i <= index ? "passed" : ""}`} title={`${gate} gate`} />}
            <span className="dot">{done && <Icon name="check" />}</span>
            <span>{label(stage)}</span>
          </div>
        );
      })}
    </div>
  );
}
