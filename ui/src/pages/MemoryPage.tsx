import { useCallback, useEffect, useState } from "react";
import { ApiError, type Api } from "../lib/api";
import type { FactStatus, RepoFact } from "../lib/types";

const BADGE: Record<FactStatus, string> = { ACTIVE: "ok", CANDIDATE: "warn", DISABLED: "" };

/**
 * What agents learned about repositories. Active facts are given to agents in later runs (after their citations are
 * re-checked); candidates wait for their run's pull request to be merged. Approvers can activate or disable any fact.
 */
export function MemoryPage(props: { api: Api; canModerate: boolean }) {
  const { api, canModerate } = props;
  const [repository, setRepository] = useState("");
  const [facts, setFacts] = useState<RepoFact[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (filter: string) => {
    try {
      setError(null);
      setFacts(await api.listMemory(filter.trim() || undefined));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load the memory.");
    }
  }, [api]);

  useEffect(() => { void load(""); }, [load]);

  const decide = async (fact: RepoFact, status: FactStatus) => {
    try {
      setError(null);
      const updated = await api.setFactStatus(fact.id, status);
      setFacts((current) => current?.map((f) => (f.id === updated.id ? updated : f)) ?? null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The change failed.");
    }
  };

  return (
    <div className="stack">
      <section className="card stack">
        <h1 style={{ margin: 0, fontSize: 20 }}>Repository memory</h1>
        <p className="muted small" style={{ margin: 0 }}>
          Facts agents saved while working, each citing the code that shows it. Active facts are given to agents in later
          runs if the cited lines still exist; candidates become active when their run's pull request is merged.
        </p>
        <form className="row" onSubmit={(e) => { e.preventDefault(); void load(repository); }}>
          <input aria-label="Repository" value={repository} onChange={(e) => setRepository(e.target.value)}
            placeholder="Filter by clone URL, e.g. https://github.com/acme/shop.git" style={{ flex: 1 }} />
          <button type="submit">Filter</button>
        </form>
      </section>
      {error && <div className="alert error" role="alert">{error}</div>}
      {facts === null ? <p className="muted">Loading…</p> : facts.length === 0 ? <p className="muted">Nothing learned yet.</p> : (
        facts.map((fact) => (
          <section key={fact.id} className="card stack" style={{ gap: 6 }}>
            <div className="row">
              <span className={`badge ${BADGE[fact.status]}`}>{fact.status.toLowerCase()}</span>
              <span className="muted small">{fact.repository}</span>
              <span className="spacer" />
              {fact.sourceRunId && <a className="small" href={`#/runs/${fact.sourceRunId}`}>learned in run</a>}
            </div>
            <div>{fact.fact}</div>
            {fact.citations.map((c) => (
              <div key={`${c.path}:${c.line}`} className="small"><code>{c.path}:{c.line}</code> <span className="muted">{c.snippet}</span></div>
            ))}
            {canModerate && (
              <div className="row">
                <span className="spacer" />
                {fact.status !== "ACTIVE" && <button onClick={() => void decide(fact, "ACTIVE")}>Activate</button>}
                {fact.status !== "DISABLED" && <button className="danger" onClick={() => void decide(fact, "DISABLED")}>Disable</button>}
              </div>
            )}
          </section>
        ))
      )}
    </div>
  );
}
