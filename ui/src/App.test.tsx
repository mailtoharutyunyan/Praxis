import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

describe("App bootstrap", () => {
  afterEach(() => vi.unstubAllGlobals());

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
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ authMode: "oidc", rolesClaim: "roles" })));
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/Sign-in could not be completed.*no issuer or clientId/);
  });
});
