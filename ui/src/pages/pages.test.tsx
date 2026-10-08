import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Api } from "../lib/api";
import { followEvents, type FollowOptions } from "../lib/sse";
import type { Run, RunEvent, RunPage as Page } from "../lib/types";
import { MemoryPage } from "./MemoryPage";
import { RunPage } from "./RunPage";
import { RunsPage } from "./RunsPage";

vi.mock("../lib/sse", () => ({ followEvents: vi.fn(() => () => undefined) }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}

const runOf = (id: string, title: string, state: Run["state"] = "IMPLEMENTING", version = 1): Run => ({
  id, state, risk: "LOW", gates: [], pendingGate: null, resumeState: null, fixIterations: 0, reviewLoops: 0,
  usage: { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, costUsd: 0 }, version,
  createdAt: "2026-10-08T10:00:00Z", updatedAt: "2026-10-08T10:00:00Z",
  progress: { percent: 40, phase: "Implementing", step: 4, steps: 9, activity: "", waiting: false, finished: false },
  task: { id: "t", origin: "PROMPT", externalRef: null, title, description: "", scmKind: "GITHUB",
    cloneUrl: "https://github.com/acme/shop.git", baseBranch: null, trust: "TRUSTED", requestedBy: "alice",
    createdAt: "2026-10-08T10:00:00Z" },
});

const A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const token = async () => "t";

afterEach(() => vi.clearAllMocks());

describe("RunPage", () => {
  it("ignores a slow response for the previous run after navigating to another", async () => {
    const slowA = deferred<Run>();
    const getRun = vi.fn((id: string) => (id === A ? slowA.promise : Promise.resolve(runOf(B, "Run B"))));
    const api = { getRun, eventStreamUrl: (id: string) => `/events/${id}` } as unknown as Api;

    const { rerender } = render(<RunPage api={api} id={A} token={token} roles={[]} />);
    rerender(<RunPage api={api} id={B} token={token} roles={[]} />);
    expect(await screen.findByText("Run B")).toBeInTheDocument();

    await act(async () => slowA.resolve(runOf(A, "Run A")));
    expect(screen.queryByText("Run A")).toBeNull();
    expect(screen.getByText("Run B")).toBeInTheDocument();
  });

  it("keeps the newest state when overlapping refreshes resolve out of order", async () => {
    const responses = [deferred<Run>(), deferred<Run>(), deferred<Run>()];
    const getRun = vi.fn().mockImplementation(() => responses[getRun.mock.calls.length - 1].promise);
    const api = { getRun, eventStreamUrl: () => "/events" } as unknown as Api;
    render(<RunPage api={api} id={A} token={token} roles={[]} />);
    await act(async () => responses[0].resolve(runOf(A, "Run A", "IMPLEMENTING", 1)));

    const { onEvent } = vi.mocked(followEvents).mock.calls[0][0] as FollowOptions;
    const change = (seq: number, to: string): RunEvent =>
      ({ seq, type: "STATE_CHANGED", actor: "system", payload: { to }, occurredAt: "2026-10-08T10:00:00Z" });
    act(() => {
      onEvent(change(1, "VERIFYING"));
      onEvent(change(2, "REVIEWING"));
    });
    await act(async () => responses[2].resolve(runOf(A, "Run A", "REVIEWING", 3)));
    await act(async () => responses[1].resolve(runOf(A, "Run A", "VERIFYING", 2)));

    expect(screen.getAllByText("reviewing").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("pipeline").querySelector(".current")).toHaveTextContent("reviewing");
  });
});

describe("RunPage approval", () => {
  it("keeps the approver's comment when the decision request fails", async () => {
    const waiting = { ...runOf(A, "Run A", "AWAITING_APPROVAL"), gates: ["PUBLISH" as const], pendingGate: "PUBLISH" as const };
    const decide = vi.fn().mockRejectedValue(new TypeError("Failed to fetch"));
    const api = { getRun: vi.fn().mockResolvedValue(waiting), eventStreamUrl: () => "/events", decide } as unknown as Api;
    render(<RunPage api={api} id={A} token={token} roles={["approver"]} />);

    const comment = await screen.findByPlaceholderText("Optional");
    fireEvent.change(comment, { target: { value: "split the migration" } });
    fireEvent.click(screen.getByRole("button", { name: "Request changes" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("could not be sent");
    expect(decide).toHaveBeenCalledWith(A, "PUBLISH", "REQUEST_CHANGES", "split the migration");
    expect(comment).toHaveValue("split the migration");
  });
});

describe("RunsPage", () => {
  it("does not let a slow response for the previous filter replace the current list", async () => {
    const slow = deferred<Page>();
    const listRuns = vi.fn()
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValue({ items: [runOf(B, "Everything")], nextCreatedBefore: null });
    const api = { listRuns } as unknown as Api;

    render(<RunsPage api={api} canSubmit={false} navigate={vi.fn()} />);
    fireEvent.click(screen.getByRole("tab", { name: "All" }));
    expect(await screen.findByText("Everything")).toBeInTheDocument();

    await act(async () => slow.resolve({ items: [runOf(A, "Needs me only")], nextCreatedBefore: null }));
    expect(screen.queryByText("Needs me only")).toBeNull();
    expect(screen.getByText("Everything")).toBeInTheDocument();
    expect((listRuns.mock.calls[0][3] as AbortSignal).aborted).toBe(true);
  });
});


describe("MemoryPage", () => {
  it("lists facts and lets approvers disable one", async () => {
    const fact = { id: "f1", repository: "github.com/acme/shop", fact: "Integration tests need -Pit.",
      citations: [{ path: "pom.xml", line: 12, snippet: "<id>it</id>" }], status: "ACTIVE" as const,
      sourceRunId: "r1", createdAt: "2026-10-08T10:00:00Z", expiresAt: "2026-11-05T10:00:00Z" };
    const api = {
      listMemory: vi.fn().mockResolvedValue([fact]),
      setFactStatus: vi.fn().mockResolvedValue({ ...fact, status: "DISABLED" }),
    } as unknown as Api;
    render(<MemoryPage api={api} canModerate />);
    expect(await screen.findByText("Integration tests need -Pit.")).toBeTruthy();
    expect(screen.getByText("pom.xml:12")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Disable" }));
    await waitFor(() => expect(screen.getByText("disabled")).toBeTruthy());
    expect(api.setFactStatus).toHaveBeenCalledWith("f1", "DISABLED");
  });
});
