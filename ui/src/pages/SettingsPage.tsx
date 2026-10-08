import { useEffect, useState } from "react";
import { ConnectorForm } from "../components/ConnectorForm";
import { ApiError, type Api } from "../lib/api";
import type { Connector } from "../lib/setup";

const STATUS_BADGE = { CONFIGURED: "ok", SKIPPED: "neutral", PENDING: "warn" } as const;

/** Connectors for admins: change tokens, add Jira or Slack later, or skip them again. */
export function SettingsPage({ api, onChanged }: { api: Api; onChanged: () => void }) {
  const [connectors, setConnectors] = useState<Connector[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.connectors().then(setConnectors, (e: unknown) =>
      setError(e instanceof ApiError ? e.message : "The connectors could not be loaded."));
  }, [api]);

  const replace = (next: Connector) => {
    setConnectors((list) => list?.map((c) => (c.definition.id === next.definition.id ? next : c)) ?? null);
    setOpen(null);
    onChanged();
  };

  if (error) return <div className="alert error" role="alert">{error}</div>;
  if (!connectors) return <p className="muted">Loading…</p>;
  return (
    <div className="stack">
      <h1 style={{ margin: 0, fontSize: 20 }}>Settings</h1>
      {connectors.map((connector) => (
        <div className="card stack" key={connector.definition.id}>
          <div className="row">
            <b>{connector.definition.title}</b>
            <span className={`badge ${STATUS_BADGE[connector.status]}`}>{connector.status.toLowerCase()}</span>
            <span className="spacer" />
            <button type="button" onClick={() => setOpen(open === connector.definition.id ? null : connector.definition.id)}>
              {open === connector.definition.id ? "Close" : "Edit"}
            </button>
          </div>
          {open === connector.definition.id && (
            <ConnectorForm api={api} connector={connector} onSaved={replace} onSkipped={replace} />
          )}
        </div>
      ))}
    </div>
  );
}
