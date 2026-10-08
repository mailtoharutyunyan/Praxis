import { useCallback, useEffect, useState } from "react";
import { CliSnippets, CopyButton } from "../components/CliSnippets";
import { Icon } from "../components/Icon";
import { ApiError, type Api } from "../lib/api";
import type { ApiTokenView, CreatedToken, SignedIn } from "../lib/setup";

const GRANTABLE = ["viewer", "operator", "approver"];

function day(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString() : "—";
}

function message(e: unknown, fallback: string): string {
  return e instanceof ApiError ? e.message : fallback;
}

/**
 * The signed-in user's account: password (built-in sign-in), personal API tokens, and how to connect an AI CLI
 * (Claude Code, Codex, Gemini CLI) to this app over MCP with one of them.
 */
export function AccountPage({ api, subject, roles, local, onPasswordChanged }: {
  api: Api;
  subject: string;
  roles: string[];
  local: boolean;
  onPasswordChanged: (signedIn: SignedIn) => void;
}) {
  const grantable = GRANTABLE.filter((r) => roles.includes(r));
  const [tokens, setTokens] = useState<ApiTokenView[] | null>(null);
  const [created, setCreated] = useState<CreatedToken | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [chosen, setChosen] = useState<string[]>(grantable.filter((r) => r !== "approver"));

  const load = useCallback(() => {
    api.tokens().then(setTokens, (e: unknown) => setError(message(e, "Could not load your tokens.")));
  }, [api]);
  useEffect(load, [load]);

  const create = async (form: HTMLFormElement) => {
    const data = new FormData(form);
    setBusy(true);
    setError(null);
    try {
      setCreated(await api.createToken(String(data.get("name")), chosen, Number(data.get("expiresInDays"))));
      form.reset();
      load();
    } catch (e) {
      setError(message(e, "Could not create the token."));
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (token: ApiTokenView) => {
    setError(null);
    try {
      await api.revokeToken(token.id);
      if (created?.details.id === token.id) setCreated(null);
      load();
    } catch (e) {
      setError(message(e, "Could not revoke the token."));
    }
  };

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Account</h1>
          <p>Signed in as <b>{subject}</b> · {roles.join(", ")}</p>
        </div>
      </div>
      <div className="stack">
        {error && <div className="alert error" role="alert">{error}</div>}

        <section className="card stack" aria-label="API tokens">
          <div>
            <h2 className="card-title" style={{ margin: 0 }}><Icon name="shield" />API tokens</h2>
            <p className="muted small" style={{ margin: "4px 0 0" }}>
              For scripts and AI CLIs. A token acts as you with the roles you give it (never admin), expires, and can be
              revoked at any time. It is shown once.
            </p>
          </div>
          {grantable.length === 0 ? (
            <p className="muted small">Your roles cannot be given to a token.</p>
          ) : (
            <form className="form-grid" onSubmit={(e) => { e.preventDefault(); void create(e.currentTarget); }}>
              <label>Name<input name="name" required maxLength={100} placeholder="e.g. Claude Code on my laptop" /></label>
              <label>Expires in
                <select name="expiresInDays" defaultValue="90">
                  <option value="7">7 days</option>
                  <option value="30">30 days</option>
                  <option value="90">90 days</option>
                  <option value="365">1 year</option>
                </select>
              </label>
              <fieldset className="row" style={{ border: "none", padding: 0, gap: 14 }}>
                <legend className="small" style={{ padding: 0, marginBottom: 6 }}>Roles</legend>
                {grantable.map((role) => (
                  <label key={role} className="row" style={{ display: "flex", fontWeight: 500 }}>
                    <input type="checkbox" checked={chosen.includes(role)}
                      onChange={(e) => setChosen((c) => e.target.checked ? [...c, role] : c.filter((r) => r !== role))} />
                    {role}
                  </label>
                ))}
              </fieldset>
              <div className="row" style={{ alignSelf: "end" }}>
                <span className="spacer" />
                <button className="primary" type="submit" disabled={busy || chosen.length === 0}><Icon name="plus" />Create token</button>
              </div>
            </form>
          )}
          {created && (
            <div className="callout ok" role="status">
              <Icon name="check" />
              <div className="stack" style={{ gap: 8, minWidth: 0, flex: 1 }}>
                <b>Copy your new token now. It will not be shown again.</b>
                <div className="row"><code style={{ overflowWrap: "anywhere" }}>{created.token}</code><CopyButton text={created.token} /></div>
              </div>
            </div>
          )}
          {tokens && tokens.length > 0 && (
            <div className="table-scroll">
              <table>
                <thead><tr><th>Name</th><th>Token</th><th>Roles</th><th>Last used</th><th>Expires</th><th /></tr></thead>
                <tbody>
                  {tokens.map((t) => (
                    <tr key={t.id}>
                      <td><b>{t.name}</b></td>
                      <td className="mono">{t.hint}</td>
                      <td className="small">{t.roles.join(", ")}</td>
                      <td className="small">{day(t.lastUsedAt)}</td>
                      <td className="small">{t.active ? day(t.expiresAt) : <span className="badge neutral">{t.revokedAt ? "revoked" : "expired"}</span>}</td>
                      <td style={{ textAlign: "right" }}>{t.active && <button className="danger" onClick={() => void revoke(t)}>Revoke</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="card stack" aria-label="Connect an AI CLI">
          <div>
            <h2 className="card-title" style={{ margin: 0 }}><Icon name="terminal" />Connect an AI CLI</h2>
            <p className="muted small" style={{ margin: "4px 0 0" }}>
              Work with runs from Claude Code, Codex or Gemini CLI over MCP: submit tasks, follow progress, read specs and
              diffs, ask for changes. Approving gates stays in this web UI.
            </p>
          </div>
          <CliSnippets token={created?.token} />
        </section>

        {local && <PasswordCard api={api} onChanged={onPasswordChanged} />}
      </div>
    </div>
  );
}

function PasswordCard({ api, onChanged }: { api: Api; onChanged: (signedIn: SignedIn) => void }) {
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const submit = async (form: HTMLFormElement) => {
    const data = new FormData(form);
    setError(null);
    setDone(false);
    if (data.get("next") !== data.get("confirm")) {
      setError("The new passwords do not match.");
      return;
    }
    try {
      onChanged(await api.changePassword(String(data.get("current")), String(data.get("next"))));
      form.reset();
      setDone(true);
    } catch (e) {
      setError(message(e, "Could not change the password."));
    }
  };
  return (
    <section className="card stack" aria-label="Password">
      <div>
        <h2 className="card-title" style={{ margin: 0 }}><Icon name="user" />Password</h2>
        <p className="muted small" style={{ margin: "4px 0 0" }}>Changing it signs you out everywhere else.</p>
      </div>
      <form className="form-grid" onSubmit={(e) => { e.preventDefault(); void submit(e.currentTarget); }}>
        <label>Current password<input name="current" type="password" required autoComplete="current-password" /></label>
        <span />
        <label>New password<input name="next" type="password" required minLength={10} autoComplete="new-password" /></label>
        <label>Confirm new password<input name="confirm" type="password" required autoComplete="new-password" /></label>
        {error && <div className="alert error" role="alert" style={{ gridColumn: "1 / -1" }}>{error}</div>}
        {done && <div className="alert success" role="status" style={{ gridColumn: "1 / -1" }}>Password changed.</div>}
        <div className="row" style={{ gridColumn: "1 / -1" }}><span className="spacer" /><button className="primary" type="submit">Change password</button></div>
      </form>
    </section>
  );
}
