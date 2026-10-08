import { useState, type ReactNode } from "react";
import { ApiError, type Api } from "../lib/api";
import { Icon, type IconName } from "./Icon";
import { itemSecretKey, type Connector, type ConnectorField, type ConnectorUpdate, type TestResult } from "../lib/setup";

type Item = Record<string, string>;

export const CONNECTOR_ICONS: Record<string, IconName> = {
  app: "globe", git: "branch", models: "cpu", jira: "ticket", slack: "slack", webhooks: "link",
};
type Values = Record<string, string | Item[]>;

const OPTION_LABELS: Record<string, string> = {
  GITHUB: "GitHub", GITLAB: "GitLab", BITBUCKET: "Bitbucket", AZURE_DEVOPS: "Azure DevOps",
  anthropic: "Anthropic (Claude)", openai: "OpenAI", "azure-openai": "Azure OpenAI", bedrock: "Amazon Bedrock",
  "google-genai": "Google Gemini", ollama: "Ollama (local)", cloud: "Jira Cloud", "data-center": "Jira Data Center",
};

function initial(connector: Connector): Values {
  const values: Values = {};
  for (const field of connector.definition.fields) {
    const stored = connector.config[field.name];
    if (field.type === "list") {
      const items = Array.isArray(stored)
        ? stored.map((item) => Object.fromEntries(Object.entries(item as Record<string, unknown>).map(([k, v]) => [k, String(v ?? "")])))
        : [];
      // A required list starts with one entry to fill in.
      values[field.name] = items.length === 0 && field.required ? [emptyItem(field)] : items;
    } else if (field.type !== "secret") {
      // The app's own address is where the browser is now; suggest it.
      const suggested = field.name === "publicUrl" ? window.location.origin : "";
      values[field.name] = typeof stored === "string" && stored !== "" ? stored : field.defaultValue ?? suggested;
    }
  }
  return values;
}

function emptyItem(field: ConnectorField): Item {
  return Object.fromEntries(field.itemFields.filter((f) => f.type !== "secret").map((f) => [f.name, f.defaultValue ?? ""]));
}

function message(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * A connector's form, rendered from its definition: fields, list entries (such as code hosts), secrets (write-only:
 * a stored secret shows as set and is kept when left empty), and Test / Save / Skip.
 */
export function ConnectorForm({ api, connector, onSaved, onSkipped, saveLabel = "Save", describe = true }: {
  api: Api;
  connector: Connector;
  onSaved: (saved: Connector) => void;
  onSkipped?: (skipped: Connector) => void;
  saveLabel?: string;
  describe?: boolean;
}) {
  const definition = connector.definition;
  const [values, setValues] = useState<Values>(() => initial(connector));
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<"save" | "test" | "skip" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [test, setTest] = useState<TestResult | null>(null);

  const update = (): ConnectorUpdate => ({ config: values, secrets });
  const setValue = (name: string, value: string | Item[]) => setValues((v) => ({ ...v, [name]: value }));
  const run = async (kind: "save" | "test" | "skip") => {
    setBusy(kind);
    setError(null);
    if (kind !== "test") setTest(null);
    try {
      if (kind === "test") setTest(await api.testConnector(definition.id, update()));
      if (kind === "save") onSaved(await api.saveConnector(definition.id, update()));
      if (kind === "skip") onSkipped?.(await api.skipConnector(definition.id));
    } catch (e) {
      setError(message(e, kind === "test" ? "The test could not run." : "Could not save."));
    } finally {
      setBusy(null);
    }
  };

  const input = (field: ConnectorField, value: string, onChange: (v: string) => void, secretKey?: string) => {
    const id = `${definition.id}-${secretKey ?? field.name}`;
    if (field.type === "select") {
      return (
        <select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
          {field.options.map((o) => <option key={o} value={o}>{OPTION_LABELS[o] ?? o}</option>)}
        </select>
      );
    }
    if (field.type === "secret") {
      const stored = secretKey !== undefined && connector.secretsSet.includes(secretKey);
      return (
        <input id={id} type="password" autoComplete="new-password" value={value} onChange={(e) => onChange(e.target.value)}
          required={field.required && !stored} placeholder={stored ? "Stored. Leave empty to keep it" : ""} />
      );
    }
    return (
      <input id={id} type={field.type === "url" ? "url" : "text"} value={value} required={field.required}
        onChange={(e) => onChange(e.target.value)} placeholder={field.defaultValue ?? ""} />
    );
  };

  const labelled = (field: ConnectorField, control: ReactNode, key: string) => (
    <label key={key}>
      {field.label}{field.required ? "" : " (optional)"}
      {control}
      {field.help && <span className="muted small">{field.help}</span>}
    </label>
  );

  const renderList = (field: ConnectorField) => {
    const items = (values[field.name] as Item[]) ?? [];
    const keyField = field.keyField ?? field.itemFields[0]?.name ?? "key";
    const setItem = (index: number, name: string, value: string) =>
      setValue(field.name, items.map((item, i) => (i === index ? { ...item, [name]: value } : item)));
    return (
      <fieldset key={field.name} className="stack" style={{ border: "none", padding: 0, gap: 10 }}>
        <legend className="small" style={{ padding: 0, marginBottom: 2 }}>{field.label}</legend>
        {field.help && <span className="muted small">{field.help}</span>}
        {items.map((item, index) => (
          <div key={index} className="list-item">
            <div className="form-grid">
              {field.itemFields.map((itemField) => {
                if (itemField.type === "secret") {
                  const key = itemSecretKey(field.name, (item[keyField] ?? "").trim(), itemField.name);
                  return labelled(itemField, input(itemField, secrets[key] ?? "", (v) => setSecrets((s) => ({ ...s, [key]: v })), key),
                    `${index}-${itemField.name}`);
                }
                return labelled(itemField, input(itemField, item[itemField.name] ?? "", (v) => setItem(index, itemField.name, v)),
                  `${index}-${itemField.name}`);
              })}
            </div>
            <div className="row"><span className="spacer" />
              <button type="button" className="ghost" onClick={() => setValue(field.name, items.filter((_, i) => i !== index))}><Icon name="x" />Remove</button>
            </div>
          </div>
        ))}
        <div><button type="button" onClick={() => setValue(field.name, [...items, emptyItem(field)])}><Icon name="plus" />Add {field.label.toLowerCase().replace(/s$/, "")}</button></div>
      </fieldset>
    );
  };

  return (
    <form className="stack" aria-label={definition.title} onSubmit={(e) => { e.preventDefault(); void run("save"); }}>
      {describe && <p className="muted" style={{ margin: 0 }}>{definition.description}</p>}
      <div className="form-section">
        {definition.fields.map((field) => {
          if (field.type === "list") return renderList(field);
          if (field.type === "secret") {
            return labelled(field, input(field, secrets[field.name] ?? "", (v) => setSecrets((s) => ({ ...s, [field.name]: v })), field.name), field.name);
          }
          return labelled(field, input(field, (values[field.name] as string) ?? "", (v) => setValue(field.name, v)), field.name);
        })}
      </div>
      {Object.keys(connector.webhooks).length > 0 && (
        <div className="stack" style={{ gap: 6 }}>
          <span className="small muted">Point the sender at</span>
          {Object.entries(connector.webhooks).map(([name, url]) => (
            <div key={name} className="endpoint"><span className="badge plain neutral">{name}</span><code>{url}</code></div>
          ))}
        </div>
      )}
      {test && <div className={`alert ${test.ok ? "success" : "error"}`} role="status">{test.ok ? "✓ " : "✗ "}{test.message}</div>}
      {error && <div className="alert error" role="alert">{error}</div>}
      <div className="row">
        {definition.testable && (
          <button type="button" disabled={busy !== null} onClick={() => void run("test")}><Icon name="spark" />{busy === "test" ? "Testing…" : "Test connection"}</button>
        )}
        <span className="spacer" />
        {!definition.required && onSkipped && (
          <button type="button" className="ghost" disabled={busy !== null} onClick={() => void run("skip")}>{busy === "skip" ? "Skipping…" : "Skip"}</button>
        )}
        <button className="primary" type="submit" disabled={busy !== null}><Icon name="check" />{busy === "save" ? "Saving…" : saveLabel}</button>
      </div>
    </form>
  );
}
