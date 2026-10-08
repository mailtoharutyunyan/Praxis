import { useState } from "react";
import { ApiError } from "../lib/api";
import { Icon } from "./Icon";

/** Ask for changes to the run's open pull request; the agent revises the same branch and a human approves the push. */
export function RevisionPanel(props: { onRequest: (text: string, location: string) => Promise<void> }) {
  const [text, setText] = useState("");
  const [location, setLocation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await props.onRequest(text.trim(), location.trim());
      setText("");
      setLocation("");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The request failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card stack" aria-label="Request changes">
      <h2 className="card-title" style={{ margin: 0 }}><Icon name="message" />Request changes</h2>
      <p className="muted small" style={{ margin: 0 }}>
        The agent revises the open pull request on the same branch; you approve the push again before it is published.
        Reviewers can also comment on the pull request mentioning the bot.
      </p>
      <textarea aria-label="Requested change" rows={4} value={text} onChange={(e) => setText(e.target.value)}
        placeholder="What should change?" />
      <input aria-label="File and line" value={location} onChange={(e) => setLocation(e.target.value)}
        placeholder="Optional: file and line, e.g. src/App.java:42" />
      {error && <div className="alert error" role="alert">{error}</div>}
      <div className="row">
        <span className="spacer" />
        <button disabled={busy || text.trim() === ""} onClick={() => void submit()}>Request changes</button>
      </div>
    </section>
  );
}
