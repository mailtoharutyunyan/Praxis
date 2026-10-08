import { useCallback, useEffect, useMemo, useState } from "react";
import { Api } from "./lib/api";
import { devSession, loadConfig, localSession, oidcSession, storeLocalToken, type Session, type UiConfig } from "./lib/auth";
import { getSetup, type SetupStatus } from "./lib/setup";
import { Icon, Logo } from "./components/Icon";
import { MemoryPage } from "./pages/MemoryPage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { RunPage } from "./pages/RunPage";
import { RunsPage } from "./pages/RunsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { SignInPage } from "./pages/SignInPage";
import { AccountPage } from "./pages/AccountPage";
import { UsersPage } from "./pages/UsersPage";

/** Hash routes keep deep links (e.g. from Jira comments) working without server-side routing. */
function useHashRoute(): [string, (path: string) => void] {
  const read = () => window.location.hash.replace(/^#/, "") || "/";
  const [path, setPath] = useState(read);
  useEffect(() => {
    const onChange = () => setPath(read());
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return [path, (next: string) => { window.location.hash = next; }];
}

function reason(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? `${fallback} ${error.message}` : fallback;
}

export function App() {
  const [config, setConfig] = useState<UiConfig | null>(null);
  const [setup, setSetup] = useState<SetupStatus | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [version, setVersion] = useState(0);
  const [failure, setFailure] = useState<string | null>(null);
  const [path, navigate] = useHashRoute();
  const bump = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    loadConfig().then(setConfig, (e: unknown) => setFailure(reason(e, "The configuration could not be loaded.")));
  }, []);
  // Setup status decides whether onboarding shows; it is public and re-read when the session changes.
  useEffect(() => {
    if (!config) return;
    getSetup().then(setSetup, (e: unknown) => setFailure(reason(e, "The setup status could not be loaded.")));
  }, [config, version]);
  useEffect(() => {
    if (!config) return;
    if (config.authMode === "local") {
      setSession(localSession(bump));
      return;
    }
    if (config.authMode !== "oidc") {
      setSession(devSession(config, bump));
      return;
    }
    let current = true;
    oidcSession(config, bump).then(
      (next) => { if (current) setSession(next); },
      (e: unknown) => { if (current) setFailure(reason(e, "Sign-in could not be completed.")); },
    );
    return () => { current = false; };
  }, [config, version, bump]);

  const api = useMemo(() => (session ? new Api(session.token) : null), [session]);
  const attention = useAttentionCount(api, Boolean(session?.signedIn && setup?.complete));

  if (failure) {
    return (
      <div className="auth">
        <div className="auth-card">
          <Logo />
          <div className="alert error" role="alert"><b>The app could not start.</b> {failure}</div>
          {/* Reload without the query, so a failed sign-in response is not processed again. */}
          <div><button onClick={() => window.location.replace(window.location.pathname + window.location.hash)}>Try again</button></div>
        </div>
      </div>
    );
  }
  if (!session || !api || !setup || !config) return <div className="auth"><p className="muted">Loading…</p></div>;

  const local = config.authMode === "local";
  const adminPending = setup.steps.some((s) => s.id === "admin" && s.state === "PENDING");
  if (local && (adminPending || !session.signedIn)) {
    return <SignInPage firstRun={adminPending} onSignedIn={(signedIn) => { storeLocalToken(signedIn.token); bump(); }} />;
  }
  const isAdmin = session.roles.includes("admin");
  const runMatch = path.match(/^\/runs\/([0-9a-f-]{36})$/);
  const section = ["/memory", "/settings", "/account", "/users"].includes(path) ? path.slice(1) : "runs";
  const nav = setup.complete && session.signedIn ? [
    { key: "runs", href: "#/", icon: "runs" as const, label: "Runs", count: attention },
    { key: "memory", href: "#/memory", icon: "memory" as const, label: "Memory", count: 0 },
    { key: "account", href: "#/account", icon: "terminal" as const, label: "API & AI CLI", count: 0 },
    ...(isAdmin ? [{ key: "settings", href: "#/settings", icon: "settings" as const, label: "Settings", count: 0 }] : []),
    ...(isAdmin && local ? [{ key: "users", href: "#/users", icon: "user" as const, label: "Users", count: 0 }] : []),
  ] : [];
  const name = session.subject ?? "signed out";

  return (
    <div className="app">
      <aside className="sidebar">
        <a className="brand" href="#/"><Logo /><span>Agentic SDLC<small>Agentic delivery</small></span></a>
        {nav.length > 0 && <div className="nav-label">Workspace</div>}
        <nav aria-label="Main">
          {nav.map((item) => (
            <a key={item.key} className={`nav-item ${section === item.key ? "active" : ""}`} href={item.href}
              aria-current={section === item.key ? "page" : undefined}>
              <Icon name={item.icon} />{item.label}
              {item.count > 0 && <span className="count" title="Runs waiting for you">{item.count}</span>}
            </a>
          ))}
        </nav>
        <div className="sidebar-foot">
          {session.signedIn ? (
            <>
              <a className="user" href="#/account" style={{ color: "inherit", textDecoration: "none" }} title="Account">
                <span className="avatar" aria-hidden="true">{name.slice(0, 2).toUpperCase()}</span>
                <div style={{ minWidth: 0 }}>
                  <div className="user-name">{name}</div>
                  <div className="user-roles">{session.roles.join(" · ") || "no roles"}</div>
                </div>
              </a>
              <button className="ghost" onClick={() => void session.signOut()}><Icon name="logout" />Sign out</button>
            </>
          ) : <button className="primary" onClick={() => void session.signIn()}>Sign in</button>}
        </div>
      </aside>
      <header className="topbar-mobile">
        <a className="brand" href="#/" style={{ padding: 0 }}><Logo />Agentic SDLC</a>
        <nav aria-label="Main (compact)">
          {nav.map((item) => (
            <a key={item.key} className={`nav-item ${section === item.key ? "active" : ""}`} href={item.href} title={item.label}>
              <Icon name={item.icon} />
            </a>
          ))}
        </nav>
      </header>
      <main className="main">
        <div className="content">
          {!session.signedIn ? (
            <div className="card empty"><Icon name="shield" /><p>Sign in to see and approve runs.</p></div>
          ) : !setup.complete ? (
            isAdmin
              ? <OnboardingPage api={api} status={setup} onChanged={setSetup} />
              : <div className="card empty"><Icon name="settings" /><p>Setup is not finished yet. An admin needs to connect a code host and a model first.</p></div>
          ) : path === "/settings" && isAdmin ? (
            <SettingsPage api={api} onChanged={bump} />
          ) : path === "/users" && isAdmin && local ? (
            <UsersPage api={api} self={session.subject ?? ""} />
          ) : path === "/account" ? (
            <AccountPage api={api} subject={session.subject ?? ""} roles={session.roles} local={local}
              onPasswordChanged={(signedIn) => { storeLocalToken(signedIn.token); bump(); }} />
          ) : runMatch ? (
            <RunPage api={api} id={runMatch[1]} token={session.token} roles={session.roles} />
          ) : path === "/memory" ? (
            <MemoryPage api={api} canModerate={session.roles.includes("approver")} />
          ) : (
            <RunsPage api={api} canSubmit={session.roles.includes("operator")} navigate={navigate} />
          )}
        </div>
      </main>
    </div>
  );
}

/** How many runs wait for a person (a gate or a human fix); shown next to Runs in the navigation. */
function useAttentionCount(api: Api | null, enabled: boolean): number {
  const [count, setCount] = useState(0);
  useEffect(() => {
    if (!api || !enabled) return;
    let current = true;
    const load = () => api.listRuns(["AWAITING_APPROVAL", "NEEDS_HUMAN"], 100)
      .then((page) => { if (current) setCount(page.items.length); }, () => undefined);
    void load();
    const timer = setInterval(() => void load(), 10000);
    return () => { current = false; clearInterval(timer); };
  }, [api, enabled]);
  return count;
}
