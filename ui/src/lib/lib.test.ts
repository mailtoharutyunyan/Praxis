import { describe, expect, it } from "vitest";
import { claims, rolesFrom } from "./auth";
import { renderMarkdown } from "./markdown";
import { SseParser } from "./sse";

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
});
