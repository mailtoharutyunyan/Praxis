import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError, type Api } from "../lib/api";
import type { Run, RunEvent } from "../lib/types";
import { ApprovalPanel } from "./ApprovalPanel";
import { DiffView } from "./DiffView";
import { NewTaskDialog } from "./NewTaskDialog";
import { ProgressBar } from "./ProgressBar";
import { RevisionPanel } from "./RevisionPanel";
import { Timeline } from "./Timeline";

const run: Run = {
  id: "11111111-1111-1111-1111-111111111111", state: "AWAITING_APPROVAL", risk: "MEDIUM", gates: ["SPEC", "PUBLISH"],
  pendingGate: "PUBLISH", resumeState: null, fixIterations: 0, reviewLoops: 0,
  usage: { inputTokens: 1, outputTokens: 1, cacheReadTokens: 0, cacheWriteTokens: 0, costUsd: 0.01 }, version: 3,
  createdAt: "2026-10-08T10:00:00Z", updatedAt: "2026-10-08T10:01:00Z",
  task: { id: "t", origin: "PROMPT", externalRef: null, title: "Add search", description: "d", scmKind: "GITHUB",
    cloneUrl: "https://github.com/acme/shop.git", baseBranch: null, trust: "TRUSTED", requestedBy: "alice",
    createdAt: "2026-10-08T10:00:00Z", reviewPlan: false },
  progress: { percent: 87, phase: "Waiting for approval: PUBLISH gate", step: 6, steps: 9, activity: "", waiting: true, finished: false },
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

describe("RevisionPanel", () => {
  it("sends the requested change and keeps it when the request fails", async () => {
    const onRequest = vi.fn()
      .mockRejectedValueOnce(new ApiError(409, { detail: "run reached its limit of 5 revisions" }))
      .mockResolvedValueOnce(undefined);
    render(<RevisionPanel onRequest={onRequest} />);
    const box = screen.getByLabelText("Requested change") as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: "Use a constant" } });
    fireEvent.change(screen.getByLabelText("File and line"), { target: { value: "src/App.java:4" } });

    fireEvent.click(screen.getByRole("button", { name: "Request changes" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("limit of 5 revisions"));
    expect(box.value).toBe("Use a constant");

    fireEvent.click(screen.getByRole("button", { name: "Request changes" }));
    await waitFor(() => expect(box.value).toBe(""));
    expect(onRequest).toHaveBeenLastCalledWith("Use a constant", "src/App.java:4");
  });
});

describe("NewTaskDialog", () => {
  const fill = (container: HTMLElement) => {
    const field = (name: string) => container.querySelector(`[name="${name}"]`) as HTMLInputElement;
    fireEvent.change(field("title"), { target: { value: "Add a /ping endpoint" } });
    fireEvent.change(field("description"), { target: { value: "Return pong" } });
    fireEvent.change(field("cloneUrl"), { target: { value: "https://github.com/acme/shop.git" } });
  };

  it("asks for plan review by default and sends the checkbox state", async () => {
    // jsdom may lack the dialog methods.
    HTMLDialogElement.prototype.showModal = vi.fn();
    HTMLDialogElement.prototype.close = vi.fn();
    const submit = vi.fn().mockResolvedValue(run);
    const onCreated = vi.fn();
    const { container } = render(<NewTaskDialog api={{ submit } as unknown as Api} onCreated={onCreated} />);

    const box = container.querySelector('input[name="reviewPlan"]') as HTMLInputElement;
    expect(box).not.toBeNull();
    expect(box.type).toBe("checkbox");
    expect(box.checked).toBe(true);
    expect(box.closest("label")).toHaveTextContent("Review the plan before coding");

    const form = container.querySelector("form") as HTMLFormElement;
    fill(container);
    fireEvent.submit(form);
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));
    expect(submit).toHaveBeenLastCalledWith(expect.objectContaining({ reviewPlan: true }), expect.any(String));

    expect(box.checked).toBe(true);
    fill(container);
    fireEvent.click(box);
    expect(box.checked).toBe(false);
    fireEvent.submit(form);
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(2));
    expect(submit).toHaveBeenLastCalledWith(expect.objectContaining({ reviewPlan: false }), expect.any(String));
  });
});

describe("ProgressBar", () => {
  it("shows phase, step, percentage and activity", () => {
    render(<ProgressBar progress={{ percent: 42, phase: "Implementing", step: 4, steps: 9,
      activity: "coder: edit_file services/web/page.txt", waiting: false, finished: false }} />);
    expect(screen.getByRole("progressbar").getAttribute("aria-valuenow")).toBe("42");
    expect(screen.getByText("Implementing")).toBeTruthy();
    expect(screen.getByText("42%")).toBeTruthy();
    expect(screen.getByText(/step 4 of 9/)).toBeTruthy();
    expect(screen.getByText("coder: edit_file services/web/page.txt")).toBeTruthy();
  });
});
