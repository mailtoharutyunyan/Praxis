import { ApiError } from "./api";
import type { Problem } from "./types";

/** First-run setup (ADR-0007): which steps are done. Public, holds no secrets. */
export interface SetupStep {
  id: string;
  title: string;
  required: boolean;
  state: "DONE" | "SKIPPED" | "PENDING";
}

export interface SetupStatus {
  authMode: "oidc" | "dev" | "local";
  complete: boolean;
  /** Creating the first admin needs the one-time code printed in the application log. */
  setupCodeRequired?: boolean;
  steps: SetupStep[];
}

export interface SignedIn {
  token: string;
  expiresAt: string;
  username: string;
  roles: string[];
}

export interface ConnectorField {
  name: string;
  label: string;
  type: "text" | "url" | "secret" | "select" | "list";
  required: boolean;
  help?: string | null;
  defaultValue?: string | null;
  options: string[];
  itemFields: ConnectorField[];
  keyField?: string | null;
}

export interface ConnectorDefinition {
  id: string;
  title: string;
  description: string;
  required: boolean;
  testable: boolean;
  fields: ConnectorField[];
}

/** A connector as an admin sees it: settings, and which secrets are stored (never their values). */
export interface Connector {
  definition: ConnectorDefinition;
  status: "CONFIGURED" | "SKIPPED" | "PENDING";
  config: Record<string, unknown>;
  secretsSet: string[];
  webhooks: Record<string, string>;
}

export interface ConnectorUpdate {
  config: Record<string, unknown>;
  secrets: Record<string, string>;
}

export interface TestResult {
  ok: boolean;
  message: string;
}

async function publicJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    let problem: Problem = { status: response.status, title: response.statusText };
    try {
      problem = (await response.json()) as Problem;
    } catch {
      // not a problem document
    }
    throw new ApiError(response.status, problem);
  }
  return (await response.json()) as T;
}

export function getSetup(): Promise<SetupStatus> {
  return publicJson("/api/v1/setup");
}

export function createAdmin(setupCode: string, username: string, password: string): Promise<SignedIn> {
  return publicJson("/api/v1/setup/admin", { method: "POST", body: JSON.stringify({ setupCode, username, password }) });
}

export function login(username: string, password: string): Promise<SignedIn> {
  return publicJson("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
}

/** Key under which a list item's secret is stored, e.g. {@code hosts.github.com.token}. */
export function itemSecretKey(list: string, itemKey: string, field: string): string {
  return `${list}.${itemKey}.${field}`;
}

export interface ApiTokenView {
  id: string;
  name: string;
  owner: string;
  roles: string[];
  hint: string;
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
  active: boolean;
}

export interface CreatedToken {
  details: ApiTokenView;
  /** The secret: shown once, never retrievable again. */
  token: string;
}

export interface Account {
  username: string;
  roles: string[];
  createdAt: string;
  locked: boolean;
}
