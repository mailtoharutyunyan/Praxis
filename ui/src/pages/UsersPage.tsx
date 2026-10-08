import { useCallback, useEffect, useState } from "react";
import { Icon } from "../components/Icon";
import { ApiError, type Api } from "../lib/api";
import type { Account } from "../lib/setup";

const ROLES = ["viewer", "operator", "approver", "admin"];
const HELP: Record<string, string> = {
  viewer: "sees runs", operator: "submits and cancels runs", approver: "decides gates", admin: "settings and users",
};

function message(e: unknown, fallback: string): string {
  return e instanceof ApiError ? e.message : fallback;
}

function RolePicker({ value, onChange }: { value: string[]; onChange: (roles: string[]) => void }) {
  return (
    <div className="row" style={{ gap: 14 }}>
      {ROLES.map((role) => (
        <label key={role} className="row" style={{ display: "flex", fontWeight: 500 }} title={HELP[role]}>
          <input type="checkbox" checked={value.includes(role)}
            onChange={(e) => onChange(e.target.checked ? [...value, role] : value.filter((r) => r !== role))} />
          {role}
        </label>
      ))}
    </div>
  );
}

/** Built-in accounts, for admins: add people, change their roles, reset passwords, remove them. */
export function UsersPage({ api, self }: { api: Api; self: string }) {
  const [users, setUsers] = useState<Account[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newRoles, setNewRoles] = useState<string[]>(["viewer", "operator"]);
  const [editing, setEditing] = useState<{ user: string; roles: string[] } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  const load = useCallback(() => {
    api.users().then(setUsers, (e: unknown) => setError(message(e, "Could not load users.")));
  }, [api]);
  useEffect(load, [load]);

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      load();
      return true;
    } catch (e) {
      setError(message(e, "The change failed."));
      return false;
    }
  };

  return (
    <div>
      <div className="page-head">
        <div>
          <h1>Users</h1>
          <p>People who sign in to this install. Role and password changes sign the person out everywhere.</p>
        </div>
      </div>
      <div className="stack">
        {error && <div className="alert error" role="alert">{error}</div>}
        <section className="card stack" aria-label="Add a user">
          <h2 className="card-title" style={{ margin: 0 }}><Icon name="plus" />Add a user</h2>
          <form className="form-grid" onSubmit={(e) => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = new FormData(form);
            void run(() => api.createUser(String(data.get("username")), String(data.get("password")), newRoles))
              .then((ok) => { if (ok) form.reset(); });
          }}>
            <label>Username<input name="username" required autoComplete="off" /></label>
            <label>Initial password<input name="password" type="password" required minLength={10} autoComplete="new-password" />
              <span className="muted small">At least 10 characters; they can change it under Account.</span></label>
            <div style={{ gridColumn: "1 / -1" }}><RolePicker value={newRoles} onChange={setNewRoles} /></div>
            <div className="row" style={{ gridColumn: "1 / -1" }}><span className="spacer" />
              <button className="primary" type="submit" disabled={newRoles.length === 0}>Add user</button></div>
          </form>
        </section>
        <section className="card table-card">
          <div className="table-scroll">
            <table>
              <thead><tr><th>User</th><th>Roles</th><th>Since</th><th /></tr></thead>
              <tbody>
                {users?.map((u) => (
                  <tr key={u.username}>
                    <td><b>{u.username}</b>{u.username === self && <span className="muted small"> (you)</span>}
                      {u.locked && <span className="badge warn" style={{ marginLeft: 8 }}>locked</span>}</td>
                    <td>
                      {editing?.user === u.username ? (
                        <div className="row">
                          <RolePicker value={editing.roles} onChange={(roles) => setEditing({ user: u.username, roles })} />
                          <button className="primary" disabled={editing.roles.length === 0}
                            onClick={() => void run(() => api.setRoles(u.username, editing.roles)).then((ok) => ok && setEditing(null))}>Save</button>
                          <button className="ghost" onClick={() => setEditing(null)}>Cancel</button>
                        </div>
                      ) : <span className="small">{u.roles.join(", ")}</span>}
                    </td>
                    <td className="small muted">{new Date(u.createdAt).toLocaleDateString()}</td>
                    <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                      <button className="ghost" onClick={() => setEditing({ user: u.username, roles: u.roles })}>Roles</button>
                      <button className="ghost" onClick={() => {
                        const password = window.prompt(`New password for ${u.username} (at least 10 characters)`);
                        if (password) void run(() => api.resetPassword(u.username, password));
                      }}>Reset password</button>
                      {u.username !== self && (confirmDelete === u.username
                        ? <button className="danger" onClick={() => void run(() => api.deleteUser(u.username)).then(() => setConfirmDelete(null))}>Confirm delete</button>
                        : <button className="ghost" onClick={() => setConfirmDelete(u.username)}>Delete</button>)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>
  );
}
