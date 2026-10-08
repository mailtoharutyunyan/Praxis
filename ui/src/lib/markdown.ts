import DOMPurify from "dompurify";
import { marked } from "marked";

/** Model output is untrusted: render Markdown, then sanitize before it reaches the DOM. */
export function renderMarkdown(source: string): string {
  const html = marked.parse(source, { async: false, gfm: true, breaks: false }) as string;
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
}
