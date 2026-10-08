import { useEffect, useState } from "react";
import { ConnectorForm } from "../components/ConnectorForm";
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

  return (
    <div className="stack">
      <div className="card stack">
        <h1 style={{ margin: 0, fontSize: 20 }}>Set up Agentic SDLC</h1>
        <p className="muted" style={{ margin: 0 }}>
          Connect a code host and a model to start. Jira, Slack and pull request feedback are optional; skip them now and add them later in Settings.
        </p>
        <div className="progress active" aria-label="Setup progress">
          <div className="progress-head"><span>{done} of {steps.length} steps</span><span>{Math.round((done / Math.max(steps.length, 1)) * 100)}%</span></div>
          <div className="progress-track"><div className="progress-fill" style={{ width: `${(done / Math.max(steps.length, 1)) * 100}%` }} /></div>
        </div>
        <nav className="pipeline" aria-label="Setup steps">
          {steps.map((step) => (
            <button key={step.id} type="button" className={`step ${step.state !== "PENDING" ? "done" : ""} ${step.id === current ? "current" : ""}`}
              onClick={() => setCurrent(step.id)} aria-current={step.id === current ? "step" : undefined}>
              {step.title}{step.required ? " *" : ""} · {STATE_LABEL[step.state]}
            </button>
          ))}
        </nav>
      </div>
      {connector && (
        <div className="card stack" key={connector.definition.id}>
          <h2 style={{ margin: 0, fontSize: 17 }}>{connector.definition.title}{connector.definition.required ? "" : " (optional)"}</h2>
          <ConnectorForm api={api} connector={connector} saveLabel="Save and continue"
            onSaved={(saved) => void advance(saved)} onSkipped={(skipped) => void advance(skipped)} />
        </div>
      )}
      {status.complete && (
        <div className="alert" role="status">Setup is complete. <a href="#/">Go to runs</a></div>
      )}
    </div>
  );
}
