import type { Account, ApiTokenView, Connector, ConnectorUpdate, CreatedToken, SignedIn, TestResult } from "./setup";
import type { FactStatus, Gate, GateDecision, Insights, Problem, RepoFact, RiskLevel, Run, RunEvent, RunPage, RunState, ScmKind } from "./types";

export class ApiError extends Error {
  constructor(readonly status: number, readonly problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
  }
}

export interface NewTask {
  title: string;
  description: string;
  repository: { kind: ScmKind; cloneUrl: string };
  baseBranch?: string;
  /** Other repositories changed in the same run, e.g. an API's consumers; each gets its own pull request. */
  companions?: { cloneUrl: string }[];
  /** Stop for a human to review the specification even when triage rates the task low risk. */
  reviewPlan?: boolean;
}

/** Typed client for /api/v1. Every call carries the session's bearer token. */
export class Api {
  constructor(private readonly token: () => Promise<string | null>, private readonly base = "/api/v1") {}

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = await this.token();
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    if (init.body) headers.set("Content-Type", "application/json");
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const response = await fetch(this.base + path, { ...init, headers });
    if (!response.ok) {
      let problem: Problem = { status: response.status, title: response.statusText };
      try {
        problem = (await response.json()) as Problem;
      } catch {
        // not a problem document
      }
      throw new ApiError(response.status, problem);
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  listRuns(states: RunState[] = [], limit = 50, createdBefore?: string, signal?: AbortSignal): Promise<RunPage> {
    const params = new URLSearchParams({ limit: String(limit) });
    states.forEach((state) => params.append("state", state));
    if (createdBefore) params.set("createdBefore", createdBefore);
    return this.request(`/runs?${params}`, { signal });
  }

  getRun(id: string, signal?: AbortSignal): Promise<Run> {
    return this.request(`/runs/${encodeURIComponent(id)}`, { signal });
  }

  events(id: string, afterSeq = 0, limit = 500): Promise<RunEvent[]> {
    return this.request(`/runs/${encodeURIComponent(id)}/events?afterSeq=${afterSeq}&limit=${limit}`);
  }

  eventStreamUrl(id: string): string {
    return `${this.base}/runs/${encodeURIComponent(id)}/events`;
  }

  submit(task: NewTask, idempotencyKey: string): Promise<Run> {
    return this.request("/tasks", {
      method: "POST",
      body: JSON.stringify(task),
      headers: { "Idempotency-Key": idempotencyKey },
    });
  }

  decide(id: string, gate: Gate, decision: GateDecision, comment: string): Promise<Run> {
    return this.request(`/runs/${encodeURIComponent(id)}/decisions`, {
      method: "POST",
      body: JSON.stringify({ gate, decision, comment: comment || null }),
    });
  }

  cancel(id: string, reason: string): Promise<Run> {
    return this.request(`/runs/${encodeURIComponent(id)}/cancel`, { method: "POST", body: JSON.stringify({ reason }) });
  }

  listMemory(repository?: string): Promise<RepoFact[]> {
    const params = new URLSearchParams({ limit: "200" });
    if (repository) params.set("repository", repository);
    return this.request(`/memory?${params}`);
  }

  setFactStatus(id: string, status: FactStatus): Promise<RepoFact> {
    return this.request(`/memory/${encodeURIComponent(id)}/status`, { method: "POST", body: JSON.stringify({ status }) });
  }

  insights(days: number, signal?: AbortSignal): Promise<Insights> {
    return this.request(`/insights?days=${days}`, { signal });
  }

  requestRevision(id: string, text: string, location?: string): Promise<Run> {
    return this.request(`/runs/${encodeURIComponent(id)}/revisions`, {
      method: "POST", body: JSON.stringify({ text, location: location || null }),
    });
  }

  resume(id: string): Promise<Run> {
    return this.request(`/runs/${encodeURIComponent(id)}/resume`, { method: "POST" });
  }

  raiseRisk(id: string, risk: RiskLevel, reason: string): Promise<Run> {
    return this.request(`/runs/${encodeURIComponent(id)}/risk`, { method: "POST", body: JSON.stringify({ risk, reason }) });
  }

  connectors(): Promise<Connector[]> {
    return this.request("/connectors");
  }

  saveConnector(id: string, update: ConnectorUpdate): Promise<Connector> {
    return this.request(`/connectors/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(update) });
  }

  testConnector(id: string, update: ConnectorUpdate): Promise<TestResult> {
    return this.request(`/connectors/${encodeURIComponent(id)}/test`, { method: "POST", body: JSON.stringify(update) });
  }

  skipConnector(id: string): Promise<Connector> {
    return this.request(`/connectors/${encodeURIComponent(id)}/skip`, { method: "POST" });
  }

  tokens(all = false): Promise<ApiTokenView[]> {
    return this.request(`/tokens${all ? "?all=true" : ""}`);
  }

  createToken(name: string, roles: string[], expiresInDays: number): Promise<CreatedToken> {
    return this.request("/tokens", { method: "POST", body: JSON.stringify({ name, roles, expiresInDays }) });
  }

  revokeToken(id: string): Promise<void> {
    return this.request(`/tokens/${encodeURIComponent(id)}`, { method: "DELETE" });
  }

  changePassword(currentPassword: string, newPassword: string): Promise<SignedIn> {
    return this.request("/auth/password", { method: "POST", body: JSON.stringify({ currentPassword, newPassword }) });
  }

  users(): Promise<Account[]> {
    return this.request("/users");
  }

  createUser(username: string, password: string, roles: string[]): Promise<Account> {
    return this.request("/users", { method: "POST", body: JSON.stringify({ username, password, roles }) });
  }

  setRoles(username: string, roles: string[]): Promise<void> {
    return this.request(`/users/${encodeURIComponent(username)}/roles`, { method: "PUT", body: JSON.stringify({ roles }) });
  }

  resetPassword(username: string, password: string): Promise<void> {
    return this.request(`/users/${encodeURIComponent(username)}/password`, { method: "PUT", body: JSON.stringify({ password }) });
  }

  deleteUser(username: string): Promise<void> {
    return this.request(`/users/${encodeURIComponent(username)}`, { method: "DELETE" });
  }
}
