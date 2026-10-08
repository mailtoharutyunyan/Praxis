import { useRef, useState } from "react";
import { ApiError, type Api } from "../lib/api";
import type { Run, ScmKind } from "../lib/types";

const HOSTS: Record<ScmKind, string> = {
  GITHUB: "https://github.com/owner/repo.git",
  GITLAB: "https://gitlab.com/group/project.git",
  BITBUCKET: "https://bitbucket.org/workspace/repo.git",
  AZURE_DEVOPS: "https://dev.azure.com/org/project/_git/repo",
};

export function NewTaskDialog({ api, onCreated }: { api: Api; onCreated: (run: Run) => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [kind, setKind] = useState<ScmKind>("GITHUB");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // One key per opened form, so a double submit or a retry after a timeout creates one run.
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());

  const submit = async (form: HTMLFormElement) => {
    const data = new FormData(form);
    setBusy(true);
    setError(null);
    try {
      const run = await api.submit({
        title: String(data.get("title")),
        description: String(data.get("description")),
        repository: { kind, cloneUrl: String(data.get("cloneUrl")) },
        baseBranch: String(data.get("baseBranch") || "") || undefined,
      }, idempotencyKey);
      dialog.current?.close();
      form.reset();
      setIdempotencyKey(crypto.randomUUID());
      onCreated(run);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not create the task.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button className="primary" onClick={() => dialog.current?.showModal()}>New task</button>
      <dialog ref={dialog} aria-label="New task">
        <form className="stack" onSubmit={(e) => { e.preventDefault(); void submit(e.currentTarget); }}>
          <h2 style={{ margin: 0, fontSize: 17 }}>New task</h2>
          <label>Title<input name="title" required maxLength={500} placeholder="Add a /ping endpoint" /></label>
          <label>Description
            <textarea name="description" required placeholder="What should change, acceptance criteria, constraints…" />
          </label>
          <div className="row" style={{ alignItems: "end" }}>
            <label style={{ flex: "0 0 170px" }}>Provider
              <select value={kind} onChange={(e) => setKind(e.target.value as ScmKind)}>
                <option value="GITHUB">GitHub</option>
                <option value="GITLAB">GitLab</option>
                <option value="BITBUCKET">Bitbucket</option>
                <option value="AZURE_DEVOPS">Azure DevOps</option>
              </select>
            </label>
            <label style={{ flex: 1 }}>Repository (HTTPS clone URL)
              <input name="cloneUrl" required type="url" pattern="https://.*" placeholder={HOSTS[kind]} />
            </label>
          </div>
          <label>Base branch<input name="baseBranch" placeholder="default branch" /></label>
          {error && <div className="alert error" role="alert">{error}</div>}
          <div className="row">
            <span className="spacer" />
            <button type="button" onClick={() => dialog.current?.close()}>Cancel</button>
            <button className="primary" type="submit" disabled={busy}>{busy ? "Creating…" : "Create run"}</button>
          </div>
        </form>
      </dialog>
    </>
  );
}
