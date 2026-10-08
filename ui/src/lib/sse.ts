import { TERMINAL, type RunEvent, type RunState } from "./types";

/**
 * Server-sent events over fetch, so the request can carry an Authorization header (EventSource cannot).
 * Reconnects with Last-Event-ID after network errors and normal stream ends, so no event is missed or duplicated.
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
  /** Called once the run reached a terminal state and the stream is finished for good. */
  onEnd?: () => void;
  onError?: (error: unknown) => void;
  /** First reconnect delay; doubles per consecutive failed attempt up to maxRetryMs. */
  retryMs?: number;
  maxRetryMs?: number;
}

function isTerminalTransition(event: RunEvent): boolean {
  return event.type === "STATE_CHANGED" && TERMINAL.includes(event.payload.to as RunState);
}

/**
 * Follows a run's event stream until the run reaches a terminal state or the returned function is called.
 * The server also ends streams normally (e.g. on graceful shutdown), so an end without a terminal transition
 * reconnects with backoff and resumes after the last seen event.
 */
export function followEvents(options: FollowOptions): () => void {
  const controller = new AbortController();
  const { signal } = controller;
  let lastId: string | undefined;
  let attempt = 0;
  const retryMs = options.retryMs ?? 2000;
  const maxRetryMs = options.maxRetryMs ?? 30000;

  const pause = (ms: number) => new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(timer); resolve(); }, { once: true });
  });

  const connect = async (): Promise<void> => {
    while (!signal.aborted) {
      try {
        const token = await options.token();
        const headers: Record<string, string> = { Accept: "text/event-stream" };
        if (token) headers.Authorization = `Bearer ${token}`;
        if (lastId) headers["Last-Event-ID"] = lastId;
        const response = await fetch(options.url, { headers, signal });
        if (!response.ok || !response.body) throw new Error(`event stream failed: ${response.status}`);
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        const parser = new SseParser();
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          for (const message of parser.push(value)) {
            if (message.id) lastId = message.id;
            const event = JSON.parse(message.data) as RunEvent;
            attempt = 0;
            options.onEvent(event);
            if (isTerminalTransition(event)) {
              void reader.cancel().catch(() => undefined);
              options.onEnd?.();
              return;
            }
          }
        }
        // Ended without a terminal transition (server shutdown, proxy timeout): resume below.
      } catch (error) {
        if (signal.aborted) return;
        options.onError?.(error);
      }
      await pause(Math.min(retryMs * 2 ** attempt, maxRetryMs));
      attempt++;
    }
  };
  void connect();
  return () => controller.abort();
}
