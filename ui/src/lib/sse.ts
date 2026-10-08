import type { RunEvent } from "./types";

/**
 * Server-sent events over fetch, so the request can carry an Authorization header (EventSource cannot).
 * Reconnects with Last-Event-ID after network errors, so no event is missed or duplicated.
 */
export interface SseMessage {
  id?: string;
  event?: string;
  data: string;
}

/** Incremental parser for the text/event-stream format. Feed it chunks; it returns complete messages. */
export class SseParser {
  private buffer = "";

  push(chunk: string): SseMessage[] {
    this.buffer += chunk.replace(/\r\n?/g, "\n");
    const messages: SseMessage[] = [];
    let boundary: number;
    while ((boundary = this.buffer.indexOf("\n\n")) >= 0) {
      const block = this.buffer.slice(0, boundary);
      this.buffer = this.buffer.slice(boundary + 2);
      const message: SseMessage = { data: "" };
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line === "" || line.startsWith(":")) continue;
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (field === "data") data.push(value);
        else if (field === "id") message.id = value;
        else if (field === "event") message.event = value;
      }
      if (data.length > 0) {
        message.data = data.join("\n");
        messages.push(message);
      }
    }
    return messages;
  }
}

export interface FollowOptions {
  url: string;
  token: () => Promise<string | null>;
  onEvent: (event: RunEvent) => void;
  onEnd?: () => void;
  onError?: (error: unknown) => void;
  retryMs?: number;
}

/** Follows a run's event stream until it ends (run finished) or the returned function is called. */
export function followEvents(options: FollowOptions): () => void {
  const controller = new AbortController();
  let lastId: string | undefined;
  const retryMs = options.retryMs ?? 2000;

  const connect = async (): Promise<void> => {
    while (!controller.signal.aborted) {
      try {
        const token = await options.token();
        const headers: Record<string, string> = { Accept: "text/event-stream" };
        if (token) headers.Authorization = `Bearer ${token}`;
        if (lastId) headers["Last-Event-ID"] = lastId;
        const response = await fetch(options.url, { headers, signal: controller.signal });
        if (!response.ok || !response.body) throw new Error(`event stream failed: ${response.status}`);
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        const parser = new SseParser();
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          for (const message of parser.push(value)) {
            if (message.id) lastId = message.id;
            options.onEvent(JSON.parse(message.data) as RunEvent);
          }
        }
        options.onEnd?.();
        return;
      } catch (error) {
        if (controller.signal.aborted) return;
        options.onError?.(error);
        await new Promise((resolve) => setTimeout(resolve, retryMs));
      }
    }
  };
  void connect();
  return () => controller.abort();
}
