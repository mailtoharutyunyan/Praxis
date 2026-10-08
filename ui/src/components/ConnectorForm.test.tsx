import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Api } from "../lib/api";
import type { Connector } from "../lib/setup";
import { ConnectorForm } from "./ConnectorForm";

const git: Connector = {
  definition: {
    id: "git", title: "Code hosts", description: "Where your repositories live.", required: true, testable: true,
    fields: [{
      name: "hosts", label: "Hosts", type: "list", required: true, options: [], keyField: "host", itemFields: [
        { name: "kind", label: "Provider", type: "select", required: true, options: ["GITHUB", "GITLAB"], itemFields: [], defaultValue: "GITHUB" },
        { name: "host", label: "Host", type: "text", required: true, options: [], itemFields: [] },
        { name: "token", label: "Access token", type: "secret", required: true, options: [], itemFields: [] },
      ],
    }],
  },
  status: "CONFIGURED",
  config: { hosts: [{ kind: "GITHUB", host: "github.com" }] },
  secretsSet: ["hosts.github.com.token"],
  webhooks: {},
};

describe("ConnectorForm", () => {
  it("keeps stored secrets unless a new value is typed, and sends list secrets keyed by item", async () => {
    const saveConnector = vi.fn(async () => git);
    const onSaved = vi.fn();
    const api = { saveConnector } as unknown as Api;
    render(<ConnectorForm api={api} connector={git} onSaved={onSaved} />);

    const stored = screen.getByLabelText(/Access token/);
    expect(stored).toHaveAttribute("placeholder", "Stored. Leave empty to keep it");
    expect(stored).not.toBeRequired();
    expect(screen.getByRole("button", { name: "Test connection" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Skip" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Add host" }));
    const hosts = screen.getAllByLabelText(/^Host/);
    fireEvent.change(hosts[1], { target: { value: "gitlab.acme.com" } });
    fireEvent.change(screen.getAllByLabelText(/Access token/)[1], { target: { value: "glpat-new" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(git));
    expect(saveConnector).toHaveBeenCalledWith("git", {
      config: { hosts: [{ kind: "GITHUB", host: "github.com" }, { kind: "GITHUB", host: "gitlab.acme.com" }] },
      secrets: { "hosts.gitlab.acme.com.token": "glpat-new" },
    });
  });

  it("shows a failed connection test without saving", async () => {
    const testConnector = vi.fn(async () => ({ ok: false, message: "the token was rejected (401)" }));
    const saveConnector = vi.fn();
    const api = { testConnector, saveConnector } as unknown as Api;
    render(<ConnectorForm api={api} connector={git} onSaved={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Test connection" }));
    expect(await screen.findByRole("status")).toHaveTextContent("✗ the token was rejected (401)");
    expect(saveConnector).not.toHaveBeenCalled();
  });
});
