/** Unified diff with per-line colouring. Rendered as text nodes, never as HTML. */
export function DiffView({ diff }: { diff: string }) {
  if (!diff.trim()) return <p className="muted">No changes.</p>;
  return (
    <div className="diff" role="region" aria-label="diff">
      {diff.split("\n").map((line, i) => {
        const kind = line.startsWith("diff --git") ? "file"
          : line.startsWith("@@") ? "hunk"
            : line.startsWith("+") && !line.startsWith("+++") ? "add"
              : line.startsWith("-") && !line.startsWith("---") ? "del" : "";
        return <div key={i} className={kind}>{line || " "}</div>;
      })}
    </div>
  );
}
