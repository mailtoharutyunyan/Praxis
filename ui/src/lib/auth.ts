import { UserManager, WebStorageStateStore } from "oidc-client-ts";

/** Runtime configuration served by the backend at /ui-config.json, so one build works in every environment. */
export interface UiConfig {
  authMode: "oidc" | "dev" | "local";
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
const LOCAL_TOKEN_KEY = "agentic.localToken";

export async function loadConfig(): Promise<UiConfig> {
  const response = await fetch("/ui-config.json");
  // Never fall back to developer mode: an outage or misconfiguration must not change how users sign in.
  if (!response.ok) throw new Error(`/ui-config.json returned HTTP ${response.status}.`);
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

/** Stores a token from the built-in sign-in (or clears it with null). */
export function storeLocalToken(token: string | null): void {
  storageSet(LOCAL_TOKEN_KEY, token);
}

function unexpired(token: string | null): string | null {
  const exp = claims(token).exp;
  return token && typeof exp === "number" && exp * 1000 > Date.now() ? token : null;
}

/**
 * Built-in sign-in (agentic.security.mode=local): the app issues the token; signing in happens on the sign-in page,
 * so {@code signIn} only asks the app to show it.
 */
export function localSession(onChange: () => void): Session {
  const token = unexpired(storageGet(LOCAL_TOKEN_KEY));
  const payload = claims(token);
  return {
    token: async () => unexpired(storageGet(LOCAL_TOKEN_KEY)),
    subject: (payload.sub as string | undefined) ?? null,
    roles: rolesFrom(payload, "roles"),
    signedIn: token !== null,
    signIn: async () => onChange(),
    signOut: async () => {
      // End the session on the server too (every device); the local copy goes either way.
      const current = unexpired(storageGet(LOCAL_TOKEN_KEY));
      if (current) {
        await fetch("/api/v1/auth/logout", { method: "POST", headers: { Authorization: `Bearer ${current}` } })
          .catch(() => undefined);
      }
      storageSet(LOCAL_TOKEN_KEY, null);
      onChange();
    },
  };
}

/** Same-origin page the silent-renew iframe returns to (see silent-renew.html); also registered at the provider. */
const SILENT_RENEW_PATH = "/silent-renew.html";

// One UserManager per page: each instance runs its own renew timer and event listeners, and an authorization
// code can be redeemed only once. Re-renders and repeated calls reuse it.
let shared: { manager: UserManager; ready: Promise<void> } | null = null;
let notify: () => void = () => undefined;

function userManager(config: UiConfig): { manager: UserManager; ready: Promise<void> } {
  if (shared) return shared;
  if (!config.issuer || !config.clientId) throw new Error("OIDC is enabled, but /ui-config.json has no issuer or clientId.");
  const manager = new UserManager({
    authority: config.issuer,
    client_id: config.clientId,
    redirect_uri: window.location.origin + "/",
    silent_redirect_uri: window.location.origin + SILENT_RENEW_PATH,
    post_logout_redirect_uri: window.location.origin + "/",
    scope: config.scope ?? "openid profile",
    response_type: "code",
    // Renews with the refresh token when the provider issues one, otherwise in a same-origin iframe.
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
  manager.events.addUserLoaded(() => notify());
  manager.events.addUserUnloaded(() => notify());
  const params = new URLSearchParams(window.location.search);
  const ready = params.has("state") && (params.has("code") || params.has("error"))
    ? manager.signinRedirectCallback().then(() => {
      window.history.replaceState({}, document.title, window.location.pathname + window.location.hash);
    })
    : Promise.resolve();
  shared = { manager, ready };
  return shared;
}

/** OIDC authorization code flow with PKCE; tokens are kept in session storage and renewed silently. */
export async function oidcSession(config: UiConfig, onChange: () => void): Promise<Session> {
  const { manager, ready } = userManager(config);
  notify = onChange;
  await ready;
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
