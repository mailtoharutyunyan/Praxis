import { afterEach, describe, expect, it, vi } from "vitest";
import { claims, rolesFrom, type UiConfig } from "./auth";
import { renderMarkdown } from "./markdown";
import { followEvents, SseParser } from "./sse";

describe("SseParser", () => {
  it("parses messages split across chunks, with ids, events and comments", () => {
    const parser = new SseParser();
    expect(parser.push(":keep-alive\n\nid:1\nevent:RUN_CREATED\nda")).toEqual([]);
    expect(parser.push('ta:{"seq":1}\n\nid: 2\ndata: {"seq":2}\r\n\r\n')).toEqual([
      { id: "1", event: "RUN_CREATED", data: '{"seq":1}' },
      { id: "2", data: '{"seq":2}' },
    ]);
  });

  it("joins multi-line data", () => {
    expect(new SseParser().push("data: a\ndata: b\n\n")).toEqual([{ data: "a\nb" }]);
  });
});

describe("followEvents", () => {
  const stream = (...chunks: string[]) => new Response(new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(new TextEncoder().encode(chunk)));
      controller.close();
    },
  }), { headers: { "Content-Type": "text/event-stream" } });
  const sse = (seq: number, type: string, payload: object = {}) =>
    `id:${seq}\nevent:${type}\ndata:${JSON.stringify({ seq, type, actor: "system", payload, occurredAt: "" })}\n\n`;
  const lastEventIds = (fetchMock: ReturnType<typeof vi.fn>) =>
    fetchMock.mock.calls.map(([, init]) => (init as RequestInit).headers as Record<string, string>)
      .map((headers) => headers["Last-Event-ID"]);

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("resumes after the server ends the stream normally and stops at a terminal state", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(stream(sse(1, "RUN_CREATED"), sse(2, "STATE_CHANGED", { from: "RECEIVED", to: "TRIAGING" })))
      .mockResolvedValueOnce(stream(sse(3, "STATE_CHANGED", { from: "TRIAGING", to: "CANCELLED" })));
    vi.stubGlobal("fetch", fetchMock);
    const seen: number[] = [];
    const onEnd = vi.fn();

    followEvents({ url: "/events", token: async () => "t", onEvent: (e) => seen.push(e.seq), onEnd, retryMs: 1 });

    await vi.waitFor(() => expect(onEnd).toHaveBeenCalledOnce());
    expect(seen).toEqual([1, 2, 3]);
    expect(lastEventIds(fetchMock)).toEqual([undefined, "2"]);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("backs off between failed attempts and stops reconnecting once stopped", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockRejectedValue(new TypeError("network down"));
    vi.stubGlobal("fetch", fetchMock);

    const stop = followEvents({ url: "/events", token: async () => null, onEvent: vi.fn(), retryMs: 100, maxRetryMs: 300 });

    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(100);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(199);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(300);
    expect(fetchMock).toHaveBeenCalledTimes(4);

    stop();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });
});

describe("oidcSession", () => {
  const config: UiConfig = { authMode: "oidc", issuer: "https://idp.example/realms/x", clientId: "ui", rolesClaim: "roles" };

  afterEach(() => {
    vi.doUnmock("oidc-client-ts");
    vi.resetModules();
    window.history.replaceState({}, "", "/");
  });

  it("shares one UserManager and redeems the authorization code once", async () => {
    const created: Record<string, unknown>[] = [];
    const callback = vi.fn().mockResolvedValue({});
    vi.doMock("oidc-client-ts", () => ({
      WebStorageStateStore: class {},
      UserManager: class {
        events = { addUserLoaded: vi.fn(), addUserUnloaded: vi.fn() };
        signinRedirectCallback = callback;
        getUser = vi.fn().mockResolvedValue(null);
        constructor(settings: Record<string, unknown>) {
          created.push(settings);
        }
      },
    }));
    vi.resetModules();
    const { oidcSession } = await import("./auth");
    window.history.replaceState({}, "", "/?code=abc&state=xyz#/runs");

    await Promise.all([oidcSession(config, vi.fn()), oidcSession(config, vi.fn())]);
    await oidcSession(config, vi.fn());

    expect(created).toHaveLength(1);
    expect(callback).toHaveBeenCalledOnce();
    expect(created[0].silent_redirect_uri).toBe(`${window.location.origin}/silent-renew.html`);
    expect(window.location.search).toBe("");
    expect(window.location.hash).toBe("#/runs");
  });
});

describe("auth helpers", () => {
  const token = ["e30", btoa(JSON.stringify({ sub: "alice", realm_access: { roles: ["Approver", "viewer"] } })), "sig"].join(".");

  it("reads claims and nested roles", () => {
    expect(claims(token).sub).toBe("alice");
    expect(rolesFrom(claims(token), "realm_access.roles")).toEqual(["approver", "viewer"]);
    expect(rolesFrom(claims(token), "roles")).toEqual([]);
    expect(claims("garbage")).toEqual({});
    expect(claims(null)).toEqual({});
  });
});

describe("renderMarkdown", () => {
  it("renders markdown and strips scripts and event handlers", () => {
    const html = renderMarkdown('## Plan\n\n<img src=x onerror="alert(1)"><script>alert(2)</script>\n\n- step');
    expect(html).toContain("<h2>Plan</h2>");
    expect(html).toContain("<li>step</li>");
    expect(html).not.toContain("onerror");
    expect(html).not.toContain("<script");
  });

  it("drops inline styles, style sheets, forms and frames, and opens links safely", () => {
    const html = renderMarkdown([
      '<div style="position:fixed;inset:0">Session expired</div><style>body{display:none}</style>',
      '<form action="https://evil.example"><input name="password"><button>Sign in</button></form>',
      '<iframe src="https://evil.example"></iframe>',
      "",
      "[docs](https://example.com/docs)",
    ].join("\n"));
    expect(html).toContain("Session expired");
    expect(html).not.toMatch(/style|<form|<input|<button|<iframe/);
    const link = new DOMParser().parseFromString(html, "text/html").querySelector("a")!;
    expect(link.getAttribute("href")).toBe("https://example.com/docs");
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener noreferrer");
  });
});
