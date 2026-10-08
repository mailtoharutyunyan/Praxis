import { UserManager, WebStorageStateStore } from "oidc-client-ts";

/** Runtime configuration served by the backend at /ui-config.json, so one build works in every environment. */
export interface UiConfig {
  authMode: "oidc" | "dev";
  issuer?: string;
  clientId?: string;
  scope?: string;
  rolesClaim: string;
}

export interface Session {
  token: () => Promise<string | null>;
  subject: string | null;
  roles: string[];
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  signedIn: boolean;
}

const DEV_TOKEN_KEY = "agentic.devToken";

export async function loadConfig(): Promise<UiConfig> {
  const response = await fetch("/ui-config.json");
  if (!response.ok) return { authMode: "dev", rolesClaim: "roles" };
  return (await response.json()) as UiConfig;
}

/** Decodes a JWT payload (no verification: the server verifies; the UI only adapts what it shows). */
export function claims(token: string | null): Record<string, unknown> {
  if (!token) return {};
  const part = token.split(".")[1];
  if (!part) return {};
  try {
    const json = atob(part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "="));
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function rolesFrom(payload: Record<string, unknown>, path: string): string[] {
  let node: unknown = payload;
  for (const segment of path.split(".")) {
    if (node === null || typeof node !== "object") return [];
    node = (node as Record<string, unknown>)[segment];
  }
  return Array.isArray(node) ? node.map((role) => String(role).toLowerCase()) : [];
}

function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    // storage unavailable (private mode): the session lasts until reload
  }
}

/** Developer mode: paste a token minted by scripts/dev-token.sh. */
export function devSession(config: UiConfig, onChange: () => void): Session {
  const token = storageGet(DEV_TOKEN_KEY);
  const payload = claims(token);
  return {
    token: async () => storageGet(DEV_TOKEN_KEY),
    subject: (payload.sub as string | undefined) ?? null,
    roles: rolesFrom(payload, config.rolesClaim),
    signedIn: token !== null,
    signIn: async () => {
      const pasted = window.prompt("Paste a bearer token (scripts/dev-token.sh)");
      if (pasted) {
        storageSet(DEV_TOKEN_KEY, pasted.trim());
        onChange();
      }
    },
    signOut: async () => {
      storageSet(DEV_TOKEN_KEY, null);
      onChange();
    },
  };
}

/** OIDC authorization code flow with PKCE; tokens are kept in session storage and renewed silently. */
export async function oidcSession(config: UiConfig, onChange: () => void): Promise<Session> {
  const manager = new UserManager({
    authority: config.issuer!,
    client_id: config.clientId!,
    redirect_uri: window.location.origin + "/",
    post_logout_redirect_uri: window.location.origin + "/",
    scope: config.scope ?? "openid profile",
    response_type: "code",
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
  const params = new URLSearchParams(window.location.search);
  if (params.has("code") && params.has("state")) {
    await manager.signinRedirectCallback();
    window.history.replaceState({}, document.title, window.location.pathname + window.location.hash);
  }
  manager.events.addUserLoaded(onChange);
  manager.events.addUserUnloaded(onChange);
  const user = await manager.getUser();
  const token = user && !user.expired ? user.access_token : null;
  const payload = claims(token);
  return {
    token: async () => {
      const current = await manager.getUser();
      return current && !current.expired ? current.access_token : null;
    },
    subject: (payload.sub as string | undefined) ?? null,
    roles: rolesFrom(payload, config.rolesClaim),
    signedIn: token !== null,
    signIn: () => manager.signinRedirect(),
    signOut: () => manager.signoutRedirect(),
  };
}
