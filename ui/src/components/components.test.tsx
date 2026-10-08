import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../lib/api";
import type { Run, RunEvent } from "../lib/types";
import { ApprovalPanel } from "./ApprovalPanel";
import { DiffView } from "./DiffView";
import { Timeline } from "./Timeline";

const run: Run = {
  id: "11111111-1111-1111-1111-111111111111", state: "AWAITING_APPROVAL", risk: "MEDIUM", gates: ["SPEC", "PUBLISH"],
  pendingGate: "PUBLISH", resumeState: null, fixIterations: 0, reviewLoops: 0,
  usage: { inputTokens: 1, outputTokens: 1, cacheReadTokens: 0, cacheWriteTokens: 0, costUsd: 0.01 }, version: 3,
  createdAt: "2026-10-08T10:00:00Z", updatedAt: "2026-10-08T10:01:00Z",
  task: { id: "t", origin: "PROMPT", externalRef: null, title: "Add search", description: "d", scmKind: "GITHUB",
    cloneUrl: "https://github.com/acme/shop.git", baseBranch: null, trust: "TRUSTED", requestedBy: "alice",
    createdAt: "2026-10-08T10:00:00Z" },
};

const event = (seq: number, type: RunEvent["type"], payload: Record<string, unknown>): RunEvent =>
  ({ seq, type, actor: "system", payload, occurredAt: "2026-10-08T10:00:00Z" });

const artifacts: RunEvent[] = [
  event(1, "ARTIFACT_PRODUCED", { kind: "spec", content: "## Requirements\n1. WHEN x" }),
  event(2, "ARTIFACT_PRODUCED", { kind: "diff", content: "diff --git a/A b/A\n@@ -1 +1 @@\n-old\n+new" }),
  event(3, "ARTIFACT_PRODUCED", { kind: "review", verdict: "APPROVE", content: "Looks right." }),
];

describe("ApprovalPanel", () => {
  it("shows the diff first at the publish gate and sends decisions with comments", async () => {
    const onDecide = vi.fn().mockResolvedValue(undefined);
    render(<ApprovalPanel run={run} events={artifacts} canApprove onDecide={onDecide} />);

    expect(screen.getByText(/PUBLISH gate/)).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "diff" })).toHaveTextContent("+new");
    fireEvent.click(screen.getByRole("tab", { name: "Review" }));
    expect(screen.getByText("APPROVE")).toBeInTheDocument();

    expect(screen.getByRole("button", { name: "Request changes" })).toBeDisabled();
    fireEvent.change(screen.getByPlaceholderText("Optional"), { target: { value: "rename the field" } });
    fireEvent.click(screen.getByRole("button", { name: "Request changes" }));
    await waitFor(() => expect(onDecide).toHaveBeenCalledWith("REQUEST_CHANGES", "rename the field"));
  });

  it("keeps the comment and shows the error when the decision fails, clears it on success", async () => {
    const onDecide = vi.fn()
      .mockRejectedValueOnce(new ApiError(409, { title: "Conflict", detail: "The gate was already decided." }))
      .mockResolvedValueOnce(undefined);
    render(<ApprovalPanel run={run} events={artifacts} canApprove onDecide={onDecide} />);
    const comment = screen.getByPlaceholderText("Optional");

    fireEvent.change(comment, { target: { value: "rename the field" } });
    fireEvent.click(screen.getByRole("button", { name: "Request changes" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The gate was already decided.");
    expect(comment).toHaveValue("rename the field");

    fireEvent.click(screen.getByRole("button", { name: "Request changes" }));
    await waitFor(() => expect(comment).toHaveValue(""));
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("hides decisions from non-approvers", () => {
    render(<ApprovalPanel run={run} events={artifacts} canApprove={false} onDecide={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    expect(screen.getByText(/approver role/)).toBeInTheDocument();
  });
});

describe("DiffView", () => {
  it("colours lines and never interprets HTML", () => {
    const { container } = render(<DiffView diff={"+<b>bold</b>\n-gone"} />);
    expect(container.querySelector(".add")).toHaveTextContent("+<b>bold</b>");
    expect(container.querySelector("b")).toBeNull();
    expect(container.querySelector(".del")).toHaveTextContent("-gone");
  });
});

describe("Timeline", () => {
  it("hides tool chatter unless verbose", () => {
    const events = [
      event(1, "STATE_CHANGED", { from: "RECEIVED", to: "TRIAGING" }),
      event(2, "TOOL_CALLED", { tool: "view_file", arguments: "{}" }),
      event(3, "ERROR", { kind: "ESCALATED", reason: "no API key" }),
    ];
    const { rerender } = render(<Timeline events={events} verbose={false} />);
    expect(screen.queryByText("view_file")).toBeNull();
    expect(screen.getByText(/no API key/)).toBeInTheDocument();
    rerender(<Timeline events={events} verbose />);
    expect(screen.getByText("view_file")).toBeInTheDocument();
  });
});
