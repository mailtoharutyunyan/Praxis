// Mirrors io.agenticsdlc.adapter.in.web.ApiModels.

export type RunState =
  | "RECEIVED" | "TRIAGING" | "PREPARING_CONTEXT" | "SPECIFYING" | "IMPLEMENTING" | "VERIFYING" | "REVIEWING"
  | "AWAITING_APPROVAL" | "PUBLISHING" | "PR_OPEN" | "NEEDS_HUMAN" | "DONE" | "FAILED" | "CANCELLED";

export type Gate = "SPEC" | "IMPLEMENTATION" | "PUBLISH";
export type GateDecision = "APPROVE" | "REQUEST_CHANGES" | "REJECT";
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";
export type ScmKind = "GITHUB" | "GITLAB" | "BITBUCKET" | "AZURE_DEVOPS";

export interface Usage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  costUsd: number;
}

export interface TaskInfo {
  id: string;
  origin: string;
  externalRef: string | null;
  title: string;
  description: string;
  scmKind: ScmKind;
  cloneUrl: string;
  baseBranch: string | null;
  trust: "TRUSTED" | "UNTRUSTED";
  requestedBy: string;
  createdAt: string;
}

export interface Run {
  id: string;
  state: RunState;
  risk: RiskLevel | null;
  gates: Gate[];
  pendingGate: Gate | null;
  resumeState: RunState | null;
  fixIterations: number;
  reviewLoops: number;
  usage: Usage;
  version: number;
  createdAt: string;
  updatedAt: string;
  task: TaskInfo;
}

export interface RunPage {
  items: Run[];
  nextCreatedBefore: string | null;
}

export type EventType =
  | "RUN_CREATED" | "STATE_CHANGED" | "TRIAGED" | "GATE_OPENED" | "GATE_DECIDED" | "STAGE_STARTED" | "STAGE_COMPLETED"
  | "AGENT_MESSAGE" | "TOOL_CALLED" | "TOOL_RESULT" | "COMMAND_OUTPUT" | "ARTIFACT_PRODUCED" | "USAGE_RECORDED"
  | "ERROR" | "RISK_RAISED" | "REVISION_REQUESTED";

export interface RunEvent {
  seq: number;
  type: EventType;
  actor: string;
  payload: Record<string, unknown>;
  occurredAt: string;
}

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
}

export const TERMINAL: RunState[] = ["DONE", "FAILED", "CANCELLED"];
export const PIPELINE: RunState[] = [
  "TRIAGING", "PREPARING_CONTEXT", "SPECIFYING", "IMPLEMENTING", "VERIFYING", "REVIEWING", "PUBLISHING", "PR_OPEN", "DONE",
];
