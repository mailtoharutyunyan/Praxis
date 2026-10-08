import { renderMarkdown } from "../lib/markdown";

export function Markdown({ source }: { source: string }) {
  return <div className="markdown" dangerouslySetInnerHTML={{ __html: renderMarkdown(source) }} />;
}
