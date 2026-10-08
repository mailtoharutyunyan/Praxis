import { useState } from "react";
import { ApiError } from "../lib/api";
import { createAdmin, login, type SignedIn } from "../lib/setup";
import { Logo } from "../components/Icon";

/** Built-in sign-in; on first start it creates the admin account instead. */
export function SignInPage({ firstRun, onSignedIn }: { firstRun: boolean; onSignedIn: (signedIn: SignedIn) => void }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (form: HTMLFormElement) => {
    const data = new FormData(form);
    const username = String(data.get("username") ?? "");
    const password = String(data.get("password") ?? "");
    if (firstRun && password !== String(data.get("confirm") ?? "")) {
      setError("The passwords do not match.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onSignedIn(firstRun ? await createAdmin(username, password) : await login(username, password));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not reach the server.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth">
    <div className="auth-card">
      <Logo />
      <div>
        <h1>{firstRun ? "Create the admin account" : "Sign in"}</h1>
        <p>{firstRun ? "This account has every role: it sets up connectors, submits tasks and approves runs."
          : "Agentic SDLC turns tasks into reviewed pull requests."}</p>
      </div>
      <form className="stack" onSubmit={(e) => { e.preventDefault(); void submit(e.currentTarget); }}>
        <label>Username<input name="username" required autoComplete="username" defaultValue={firstRun ? "admin" : ""} /></label>
        <label>Password
          <input name="password" type="password" required minLength={firstRun ? 10 : 1}
            autoComplete={firstRun ? "new-password" : "current-password"} />
          {firstRun && <span className="muted small">At least 10 characters.</span>}
        </label>
        {firstRun && <label>Confirm password<input name="confirm" type="password" required autoComplete="new-password" /></label>}
        {error && <div className="alert error" role="alert">{error}</div>}
        <button className="primary lg" type="submit" disabled={busy}>
          {busy ? "Please wait…" : firstRun ? "Create account" : "Sign in"}
        </button>
      </form>
    </div>
    </div>
  );
}
