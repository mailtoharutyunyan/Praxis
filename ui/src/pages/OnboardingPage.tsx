import { useEffect, useState } from "react";
import { CONNECTOR_ICONS, ConnectorForm } from "../components/ConnectorForm";
import { Icon } from "../components/Icon";
import { ApiError, type Api } from "../lib/api";
import { getSetup, type Connector, type SetupStatus } from "../lib/setup";

const STATE_LABEL = { DONE: "Done", SKIPPED: "Skipped", PENDING: "To do" } as const;

/**
 * First-run setup: one step per connector, in catalog order. Required steps (app, git, model) must be saved;
 * optional ones (Jira, Slack, pull request feedback) can be skipped and set up later in Settings.
 */
export function OnboardingPage({ api, status, onChanged }: { api: Api; status: SetupStatus; onChanged: (next: SetupStatus) => void }) {
  const [connectors, setConnectors] = useState<Connector[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const steps = status.steps.filter((s) => s.id !== "admin");
  const firstPending = steps.find((s) => s.state === "PENDING")?.id ?? steps[0]?.id;
  const [current, setCurrent] = useState<string | undefined>(firstPending);

  useEffect(() => {
    api.connectors().then(setConnectors, (e: unknown) =>
      setError(e instanceof ApiError ? e.message : "The connectors could not be loaded."));
  }, [api]);

  const advance = async (saved: Connector) => {
    setConnectors((list) => list?.map((c) => (c.definition.id === saved.definition.id ? saved : c)) ?? null);
    const next = await getSetup();
    const after = next.steps.filter((s) => s.id !== "admin");
    const index = after.findIndex((s) => s.id === saved.definition.id);
    const pending = after.slice(index + 1).find((s) => s.state === "PENDING") ?? after.find((s) => s.state === "PENDING");
    setCurrent(pending?.id ?? saved.definition.id);
    onChanged(next);
  };

  if (error) return <div className="alert error" role="alert">{error}</div>;
  if (!connectors) return <p className="muted">Loading…</p>;
  const connector = connectors.find((c) => c.definition.id === current);
  const done = steps.filter((s) => s.state !== "PENDING").length;

  const percent = Math.round((done / Math.max(steps.length, 1)) * 100);
  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Set up Agentic SDLC</h1>
          <p>Connect a code host and a model to start. Jira, Slack and pull request feedback are optional; skip them now and add them later in Settings.</p>
        </div>
      </div>
      <div className="setup">
        <aside className="card stack" style={{ padding: 12, gap: 8 }}>
          <div className="progress ok" aria-label="Setup progress" style={{ padding: "6px 8px" }}>
            <div className="progress-head"><span className="small">{done} of {steps.length} steps</span><b>{percent}%</b></div>
            <div className="progress-track"><div className="progress-fill" style={{ width: `${percent}%` }} /></div>
          </div>
          <nav className="setup-steps" aria-label="Setup steps" style={{ padding: 0 }}>
            {steps.map((step, i) => (
              <button key={step.id} type="button" className={`setup-step ${step.state === "DONE" ? "done" : step.state === "SKIPPED" ? "skipped" : ""}`}
                onClick={() => setCurrent(step.id)} aria-current={step.id === current ? "step" : undefined}>
                <span className="num">{step.state === "DONE" ? <Icon name="check" /> : i + 1}</span>
                <span>{step.title}<small>{step.required ? "Required" : "Optional"} · {STATE_LABEL[step.state]}</small></span>
              </button>
            ))}
          </nav>
        </aside>
        <div className="stack">
          {status.complete && (
            <div className="callout ok" role="status"><Icon name="check" /><div><b>Setup is complete.</b> <a href="#/">Go to runs</a></div></div>
          )}
          {connector && (
            <section className="card stack" key={connector.definition.id}>
              <div className="connector-head">
                <span className="connector-icon"><Icon name={CONNECTOR_ICONS[connector.definition.id] ?? "link"} /></span>
                <div>
                  <h2>{connector.definition.title}{connector.definition.required ? "" : " (optional)"}</h2>
                  <p>{connector.definition.required ? "Required to start runs." : "You can skip this and add it later in Settings."}</p>
                </div>
              </div>
              <ConnectorForm api={api} connector={connector} saveLabel="Save and continue"
                onSaved={(saved) => void advance(saved)} onSkipped={(skipped) => void advance(skipped)} />
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
