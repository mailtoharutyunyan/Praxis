import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Api, ApiError } from "../lib/api";
import type { Insights } from "../lib/types";
import { InsightsPage } from "./InsightsPage";

/** Insights for {@code days} days ending 2026-10-08; the given counts fill the newest days. */
function sample(days: number, counts: number[], overrides: Partial<Insights> = {}): Insights {
  const last = Date.UTC(2026, 9, 8);
  const runsPerDay = Array.from({ length: days }, (_, i) => ({
    date: new Date(last - (days - 1 - i) * 86_400_000).toISOString().slice(0, 10),
    count: counts[i - (days - counts.length)] ?? 0,
  }));
  return {
    days, from: runsPerDay[0].date + "T00:00:00Z", to: "2026-10-08T15:30:00Z",
    runsStarted: 12, finished: { DONE: 6, FAILED: 2, CANCELLED: 0 }, successRate: 0.75,
    minutesToPullRequest: { median: 25, p90: 37, count: 4 }, costUsd: 1.5, totalTokens: 9876,
    runsPerDay, ...overrides,
  } as Insights;
}

const statsText = (container: HTMLElement) => container.querySelector(".stats")?.textContent ?? "";

/** The bars: every rect in the chart, keyed by its title. */
function bars(container: HTMLElement) {
  const svg = container.querySelector(".bar-chart svg, svg.bar-chart") as SVGSVGElement | null;
  expect(svg).not.toBeNull();
  const rects = Array.from(svg!.querySelectorAll("rect"));
  const byTitle = new Map(rects.map((r) => [r.querySelector("title")?.textContent ?? "", r]));
  return { rects, byTitle };
}

describe("InsightsPage", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("loads the last 30 days and shows the headline figures", async () => {
    const insights = vi.fn(async (days: number) => sample(days, [2, 0, 4]));
    const api = { insights } as unknown as Api;
    const { container } = render(<InsightsPage api={api} />);

    await waitFor(() => expect(statsText(container)).toMatch(/9[,.\s  ]?876/));
    expect(insights.mock.calls[0][0]).toBe(30);
    const stats = statsText(container);
    expect(stats).toContain("12");
    expect(stats).toMatch(/75(\.0)?\s?%/);
    expect(stats).toMatch(/6\D+2\D+0/);
    expect(stats).toMatch(/25/);
    expect(stats).toMatch(/37/);
    expect(stats).toMatch(/\$1\.50?/);
    expect(stats).not.toContain("—");
    expect(container.querySelectorAll(".stats .stat").length).toBeGreaterThanOrEqual(6);
    expect(screen.getByRole("tab", { name: "30 days" })).toHaveAttribute("aria-selected", "true");
  });

  it("shows a dash for figures that are not known", async () => {
    const api = { insights: vi.fn(async (days: number) => sample(days, [], {
      runsStarted: 0, finished: { DONE: 0, FAILED: 0, CANCELLED: 0 }, successRate: null,
      minutesToPullRequest: { median: null, p90: null, count: 0 }, costUsd: 0, totalTokens: 0,
    })) } as unknown as Api;
    const { container } = render(<InsightsPage api={api} />);

    await waitFor(() => expect(statsText(container)).toContain("—"));
    expect(statsText(container)).not.toMatch(/NaN|null|undefined/);
  });

  it("draws one bar per day scaled to the busiest day", async () => {
    const api = { insights: vi.fn(async (days: number) => sample(days, [2, 0, 4])) } as unknown as Api;
    const { container } = render(<InsightsPage api={api} />);

    await waitFor(() => expect(container.querySelector(".bar-chart")).not.toBeNull());
    await waitFor(() => expect(bars(container).rects).toHaveLength(30));
    const { byTitle } = bars(container);
    const height = (title: string) => Number(byTitle.get(title)?.getAttribute("height"));
    expect(byTitle.has("2026-10-06: 2 runs")).toBe(true);
    expect(byTitle.has("2026-10-07: 0 runs")).toBe(true);
    expect(byTitle.has("2026-10-08: 4 runs")).toBe(true);
    expect(byTitle.has("2026-09-09: 0 runs")).toBe(true);
    expect(height("2026-10-08: 4 runs")).toBeGreaterThan(height("2026-10-06: 2 runs"));
    expect(height("2026-10-06: 2 runs")).toBeGreaterThan(height("2026-10-07: 0 runs"));
  });

  it("switches to 7 days", async () => {
    const insights = vi.fn(async (days: number) => sample(days, [3, 5]));
    const api = { insights } as unknown as Api;
    const { container } = render(<InsightsPage api={api} />);
    await waitFor(() => expect(bars(container).rects).toHaveLength(30));

    fireEvent.click(screen.getByRole("tab", { name: "7 days" }));

    await waitFor(() => expect(insights.mock.calls.some((call) => call[0] === 7)).toBe(true));
    await waitFor(() => expect(bars(container).rects).toHaveLength(7));
    expect(screen.getByRole("tab", { name: "7 days" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "30 days" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("tab", { name: "90 days" })).toBeInTheDocument();
    expect(screen.getByRole("tablist")).toHaveClass("chips");
  });

  it("shows a failed load as an alert", async () => {
    const api = { insights: vi.fn().mockRejectedValue(new ApiError(503, { title: "Unavailable", detail: "Database unavailable" })) } as unknown as Api;
    render(<InsightsPage api={api} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Database unavailable");
    expect(screen.getByRole("alert")).toHaveClass("alert", "error");
  });

  it("asks the API for the given number of days", async () => {
    const fetch = vi.fn(async () => Response.json(sample(7, [1])));
    vi.stubGlobal("fetch", fetch);
    const result = await new Api(async () => "t").insights(7);

    expect(String((fetch.mock.calls[0] as unknown[])[0])).toBe("/api/v1/insights?days=7");
    expect(result.runsPerDay).toHaveLength(7);
  });
});
