import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Api } from "../lib/api";
import type { ApiTokenView } from "../lib/setup";
import { AccountPage } from "./AccountPage";

const listed: ApiTokenView = {
  id: "t1", name: "CI script", owner: "admin", roles: ["viewer"], hint: "asdlc_AbCd…", createdAt: "2026-10-01T00:00:00Z",
  expiresAt: "2027-01-01T00:00:00Z", lastUsedAt: null, revokedAt: null, active: true,
};

describe("AccountPage", () => {
  it("creates a token, shows it once with ready-to-paste AI CLI setup, and revokes tokens", async () => {
    const tokens = vi.fn(async () => [listed]);
    const createToken = vi.fn(async () => ({ details: { ...listed, id: "t2", name: "Claude Code" }, token: "asdlc_secretvalue" }));
    const revokeToken = vi.fn(async () => undefined);
    const api = { tokens, createToken, revokeToken } as unknown as Api;
    render(<AccountPage api={api} subject="admin" roles={["viewer", "operator", "approver", "admin"]} local
      onPasswordChanged={vi.fn()} />);

    expect(await screen.findByText("CI script")).toBeInTheDocument();
    expect(screen.queryByLabelText("admin")).toBeNull();
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Claude Code" } });
    fireEvent.click(screen.getByRole("button", { name: "Create token" }));

    expect(await screen.findByText("asdlc_secretvalue")).toBeInTheDocument();
    expect(createToken).toHaveBeenCalledWith("Claude Code", ["viewer", "operator"], 90);
    const setup = screen.getByLabelText("Claude Code setup");
    expect(setup).toHaveTextContent(`claude mcp add --transport http`);
    expect(setup).toHaveTextContent(`${window.location.origin}/mcp`);
    expect(setup).toHaveTextContent("Bearer asdlc_secretvalue");

    fireEvent.click(screen.getByRole("tab", { name: "Gemini CLI" }));
    expect(screen.getByLabelText("Gemini CLI setup")).toHaveTextContent("gemini mcp add --scope user --transport http");

    fireEvent.click(screen.getAllByRole("button", { name: "Revoke" })[0]);
    await vi.waitFor(() => expect(revokeToken).toHaveBeenCalledWith("t1"));
  });

  it("offers no token roles to a user whose roles cannot be delegated", async () => {
    const api = { tokens: vi.fn(async () => []) } as unknown as Api;
    render(<AccountPage api={api} subject="ops" roles={["admin"]} local={false} onPasswordChanged={vi.fn()} />);
    expect(await screen.findByText(/cannot be given to a token/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Password")).toBeNull();
  });
});
