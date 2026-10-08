import { useEffect, useState } from "react";
import { CONNECTOR_ICONS, ConnectorForm } from "../components/ConnectorForm";
import { Icon } from "../components/Icon";
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
    <div>
      <div className="page-head">
        <div>
          <h1>Settings</h1>
          <p>Connectors for code hosts, models and the tools runs start from. Secrets are encrypted and never shown again.</p>
        </div>
      </div>
      <div className="connector-grid">
        {connectors.map((connector) => {
          const isOpen = open === connector.definition.id;
          return (
            <section className={`card connector ${isOpen ? "open" : ""}`} key={connector.definition.id} aria-label={`${connector.definition.title} connector`}>
              <div className="connector-head">
                <span className="connector-icon"><Icon name={CONNECTOR_ICONS[connector.definition.id] ?? "link"} /></span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="row">
                    <h2>{connector.definition.title}</h2>
                    <span className={`badge ${STATUS_BADGE[connector.status]}`}>{connector.status.toLowerCase()}</span>
                  </div>
                  <p>{connector.definition.description}</p>
                </div>
                <button type="button" onClick={() => setOpen(isOpen ? null : connector.definition.id)}>
                  {isOpen ? "Close" : connector.status === "CONFIGURED" ? "Edit" : "Set up"}
                </button>
              </div>
              {isOpen && <ConnectorForm api={api} connector={connector} onSaved={replace} onSkipped={replace} describe={false} />}
            </section>
          );
        })}
      </div>
    </div>
  );
}
