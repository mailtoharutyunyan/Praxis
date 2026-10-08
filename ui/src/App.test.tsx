import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const COMPLETE = { authMode: "oidc", complete: true, steps: [] };

/** Routes fetches by path; each call gets a fresh Response (a body can be read once). */
function routes(table: Record<string, () => Response | Promise<Response>>) {
  return vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
    const path = url.split("?")[0];
    const handler = table[path];
    return handler ? Promise.resolve(handler()) : Promise.resolve(new Response("", { status: 404 }));
  });
}

function token(payload: Record<string, unknown>): string {
  const encode = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
  return `${encode({ alg: "RS256" })}.${encode(payload)}.sig`;
}

describe("App bootstrap", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
    window.location.hash = "";
  });

  it("shows an error instead of a blank page when the configuration cannot be fetched", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/could not start.*configuration could not be loaded.*Failed to fetch/);
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("does not fall back to developer mode when the configuration endpoint fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 503 })));
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 503");
    expect(screen.queryByRole("button", { name: "Sign in" })).toBeNull();
  });

  it("shows an error when OIDC is enabled without an issuer", async () => {
    vi.stubGlobal("fetch", routes({
      "/ui-config.json": () => Response.json({ authMode: "oidc", rolesClaim: "roles" }),
      "/api/v1/setup": () => Response.json(COMPLETE),
    }));
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/Sign-in could not be completed.*no issuer or clientId/);
  });
});

describe("Built-in sign-in and onboarding", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const localConfig = () => Response.json({ authMode: "local", rolesClaim: "roles" });

  it("asks for the admin account on first start and then opens onboarding", async () => {
    let adminCreated = false;
    const fresh = token({ sub: "admin", roles: ["viewer", "operator", "approver", "admin"], exp: Date.now() / 1000 + 3600 });
    const fetch = routes({
      "/ui-config.json": localConfig,
      "/api/v1/setup": () => Response.json({
        authMode: "local", complete: false, steps: [
          { id: "admin", title: "Admin account", required: true, state: adminCreated ? "DONE" : "PENDING" },
          { id: "git", title: "Code hosts", required: true, state: "PENDING" },
          { id: "jira", title: "Jira", required: false, state: "PENDING" },
        ],
      }),
      "/api/v1/setup/admin": () => {
        adminCreated = true;
        return Response.json({ token: fresh, expiresAt: "2026-10-10T00:00:00Z", username: "admin", roles: ["admin"] });
      },
      "/api/v1/connectors": () => Response.json([
        { definition: { id: "git", title: "Code hosts", description: "Where your repositories live.", required: true, testable: true,
          fields: [{ name: "hosts", label: "Hosts", type: "list", required: true, options: [], keyField: "host",
            itemFields: [{ name: "host", label: "Host", type: "text", required: true, options: [], itemFields: [] },
              { name: "token", label: "Access token", type: "secret", required: true, options: [], itemFields: [] }] }] },
          status: "PENDING", config: {}, secretsSet: [], webhooks: {} },
        { definition: { id: "jira", title: "Jira", description: "Optional.", required: false, testable: true, fields: [] },
          status: "PENDING", config: {}, secretsSet: [], webhooks: {} },
      ]),
    });
    vi.stubGlobal("fetch", fetch);
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Create the admin account" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: "correct horse battery" } });
    fireEvent.change(screen.getByLabelText(/Confirm password/), { target: { value: "correct horse battery" } });
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("heading", { name: "Set up Agentic SDLC" })).toBeInTheDocument();
    expect(await screen.findByRole("form", { name: "Code hosts" })).toBeInTheDocument();
    // A required step has no Skip; an optional one does.
    expect(screen.queryByRole("button", { name: "Skip" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Jira/ }));
    expect(await screen.findByRole("button", { name: "Skip" })).toBeInTheDocument();
    const connectorsCall = fetch.mock.calls.find(([url]) => String(url) === "/api/v1/connectors") as unknown as [string, RequestInit];
    expect(new Headers(connectorsCall[1].headers).get("Authorization")).toBe(`Bearer ${fresh}`);
  });

  it("shows the sign-in page when the admin exists but nobody is signed in", async () => {
    vi.stubGlobal("fetch", routes({
      "/ui-config.json": localConfig,
      "/api/v1/setup": () => Response.json({ authMode: "local", complete: true, steps: [
        { id: "admin", title: "Admin account", required: true, state: "DONE" }] }),
    }));
    render(<App />);
    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Confirm password/)).toBeNull();
  });

  it("ignores an expired stored token", async () => {
    localStorage.setItem("agentic.localToken", token({ sub: "admin", roles: ["admin"], exp: Date.now() / 1000 - 10 }));
    vi.stubGlobal("fetch", routes({
      "/ui-config.json": localConfig,
      "/api/v1/setup": () => Response.json({ authMode: "local", complete: true, steps: [
        { id: "admin", title: "Admin account", required: true, state: "DONE" }] }),
    }));
    render(<App />);
    await waitFor(() => expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument());
  });
});
