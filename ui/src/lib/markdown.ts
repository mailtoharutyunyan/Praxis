import DOMPurify from "dompurify";
import { marked } from "marked";

// A private instance, so the link hook below never affects other DOMPurify users.
const purifier = DOMPurify(window);

// Links leave the app in a new tab without access to it (no window.opener) and without leaking the URL.
purifier.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A" && node.hasAttribute("href")) {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

/**
 * Model and ticket text is untrusted: render Markdown, then sanitize before it reaches the DOM.
 * No inline styles (they could overlay the app's own UI), no style sheets, forms or embedded frames.
 */
export function renderMarkdown(source: string): string {
  const html = marked.parse(source, { async: false, gfm: true, breaks: false }) as string;
  return purifier.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ["style", "link", "form", "input", "button", "select", "option", "textarea", "iframe", "frame", "object", "embed"],
    FORBID_ATTR: ["style"],
  });
}
