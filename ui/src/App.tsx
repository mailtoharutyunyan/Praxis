import { useCallback, useEffect, useMemo, useState } from "react";
import { Api } from "./lib/api";
import { devSession, loadConfig, oidcSession, type Session, type UiConfig } from "./lib/auth";
import { RunPage } from "./pages/RunPage";
import { RunsPage } from "./pages/RunsPage";

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

export function App() {
  const [config, setConfig] = useState<UiConfig | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [version, setVersion] = useState(0);
  const [path, navigate] = useHashRoute();
  const bump = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => { void loadConfig().then(setConfig); }, []);
  useEffect(() => {
    if (!config) return;
    if (config.authMode === "oidc") void oidcSession(config, bump).then(setSession);
    else setSession(devSession(config, bump));
  }, [config, version, bump]);

  const api = useMemo(() => (session ? new Api(session.token) : null), [session]);

  if (!session || !api) return <div className="shell"><p className="muted">Loading…</p></div>;

  const runMatch = path.match(/^\/runs\/([0-9a-f-]{36})$/);
  return (
    <div className="shell">
      <header className="topbar">
        <a className="brand" href="#/">Agentic SDLC <span>runs</span></a>
        <div className="row">
          {session.signedIn && <span className="muted small">{session.subject} · {session.roles.join(", ") || "no roles"}</span>}
          {session.signedIn
            ? <button onClick={() => void session.signOut()}>Sign out</button>
            : <button className="primary" onClick={() => void session.signIn()}>Sign in</button>}
        </div>
      </header>
      {!session.signedIn ? (
        <div className="card"><p>Sign in to see and approve runs.</p></div>
      ) : runMatch ? (
        <RunPage api={api} id={runMatch[1]} token={session.token} roles={session.roles} />
      ) : (
        <RunsPage api={api} canSubmit={session.roles.includes("operator")} navigate={navigate} />
      )}
    </div>
  );
}
