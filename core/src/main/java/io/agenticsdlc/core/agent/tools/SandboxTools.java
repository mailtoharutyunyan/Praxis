package io.agenticsdlc.core.agent.tools;

import static io.agenticsdlc.core.workspace.WorkspacePath.shellQuote;

import io.agenticsdlc.core.agent.AgentTool;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.agent.ToolException;
import io.agenticsdlc.core.agent.ToolSpec;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.WorkspacePath;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/**
 * The agent's tools, all executed against the run's sandbox (agent-computer interface, ADR-0003). Designed for
 * models: concise output, line numbers for viewing, exact-match editing that refuses ambiguous replacements, and
 * errors phrased so the model can recover.
 */
public final class SandboxTools {

	static final int MAX_FILE_BYTES = 256 * 1024;
	static final int DEFAULT_VIEW_LINES = 400;
	static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
	static final String NOISE_DIRS = "-not -path '*/.git/*' -not -path '*/node_modules/*' -not -path '*/target/*' "
			+ "-not -path '*/build/*' -not -path '*/dist/*' -not -path '*/.venv/*'";

	private final Sandbox sandbox;
	private final RepositoryCheckout checkout;
	private final Duration commandTimeout;

	public SandboxTools(Sandbox sandbox, RepositoryCheckout checkout, Duration commandTimeout) {
		this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
		this.checkout = Objects.requireNonNull(checkout, "checkout");
		this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
	}

	/** Everything the coder needs. */
	public List<AgentTool> coderTools() {
		return List.of(listFiles(), viewFile(), search(), editFile(), createFile(), runCommand(), diff());
	}

	/** Look, don't touch: the reviewer and planner get these. */
	public List<AgentTool> readOnlyTools() {
		return List.of(listFiles(), viewFile(), search(), diff());
	}

	AgentTool listFiles() {
		return tool("list_files", """
				List files and directories under a path in the repository (build output, .git and dependency \
				folders are skipped). Use it to orient yourself before reading files.""", """
				{"type":"object","properties":{
				"path":{"type":"string","description":"Directory relative to the repository root. Default: root."},
				"max_depth":{"type":"integer","description":"How deep to list, 1-6. Default 2."}}}""", false,
				(runId, call) -> {
					String path = path(call.string("path"));
					int depth = Math.clamp(call.integer("max_depth", 2), 1, 6);
					// Portable across GNU and busybox find (no -printf): directories get a trailing slash via sed.
					String find = "find " + shellQuote(path) + " -mindepth 1 -maxdepth " + depth + " " + NOISE_DIRS;
					return exec(runId, "{ " + find + " -type d | sed 's|$|/|'; " + find + " ! -type d; } 2>/dev/null"
							+ " | sort | head -500", SEARCH_TIMEOUT)
							.map(r -> r.output().isBlank() ? "(empty)" : r.output());
				});
	}

	AgentTool viewFile() {
		return tool("view_file", """
				Show a text file with line numbers. For large files, view a range with start_line/end_line \
				(1-based, inclusive). Always view a file before editing it.""", """
				{"type":"object","required":["path"],"properties":{
				"path":{"type":"string","description":"File path relative to the repository root."},
				"start_line":{"type":"integer"},"end_line":{"type":"integer"}}}""", false,
				(runId, call) -> read(runId, call.requiredString("path")).map(content -> {
					if (content.isEmpty()) {
						return "(empty file)";
					}
					String[] lines = content.split("\n", -1);
					int total = content.endsWith("\n") ? lines.length - 1 : lines.length;
					int start = Math.max(1, call.integer("start_line", 1));
					int end = Math.min(total, call.integer("end_line", start + DEFAULT_VIEW_LINES - 1));
					if (start > total) {
						throw new ToolException("start_line " + start + " is past the end of the file (" + total + " lines)");
					}
					StringBuilder out = new StringBuilder();
					for (int i = start; i <= end; i++) {
						out.append(String.format("%6d\t%s%n", i, lines[i - 1]));
					}
					if (end < total) {
						out.append("… ").append(total - end).append(" more lines; use start_line=").append(end + 1)
								.append('\n');
					}
					return out.toString();
				}));
	}

	AgentTool search() {
		return tool("search", """
				Search file contents with an extended regular expression (grep -E). Returns matching lines as \
				path:line:text, at most 200.""", """
				{"type":"object","required":["pattern"],"properties":{
				"pattern":{"type":"string","description":"Extended regular expression."},
				"path":{"type":"string","description":"File or directory to search. Default: repository root."},
				"ignore_case":{"type":"boolean"}}}""", false,
				(runId, call) -> {
					String flags = call.bool("ignore_case", false) ? "-rnIEi" : "-rnIE";
					String command = "grep " + flags + " --exclude-dir=.git --exclude-dir=node_modules "
							+ "--exclude-dir=target --exclude-dir=build --exclude-dir=dist -e "
							+ shellQuote(call.requiredString("pattern")) + " -- " + shellQuote(path(call.string("path")))
							+ " 2>/dev/null | head -200";
					return exec(runId, command, SEARCH_TIMEOUT).map(r -> r.output().isBlank() ? "No matches." : r.output());
				});
	}

	AgentTool editFile() {
		return tool("edit_file", """
				Replace text in an existing file. old_string must match the file exactly (including whitespace \
				and indentation) and be unique unless replace_all is true; include enough surrounding lines to make \
				it unique. Use create_file for new files.""", """
				{"type":"object","required":["path","old_string","new_string"],"properties":{
				"path":{"type":"string"},"old_string":{"type":"string"},"new_string":{"type":"string"},
				"replace_all":{"type":"boolean","description":"Replace every occurrence. Default false."}}}""", true,
				(runId, call) -> {
					String path = call.requiredString("path");
					String oldText = call.requiredString("old_string");
					String newText = call.requiredString("new_string");
					if (oldText.isEmpty()) {
						throw new ToolException("old_string must not be empty; use create_file to write a whole file");
					}
					if (oldText.equals(newText)) {
						throw new ToolException("old_string and new_string are identical; nothing to change");
					}
					boolean all = call.bool("replace_all", false);
					return read(runId, path).flatMap(content -> {
						int count = occurrences(content, oldText);
						if (count == 0) {
							throw new ToolException("old_string not found in " + path
									+ "; view the file and copy the text exactly");
						}
						if (count > 1 && !all) {
							throw new ToolException("old_string occurs " + count + " times in " + path
									+ "; add surrounding lines to make it unique, or set replace_all");
						}
						String updated = all ? content.replace(oldText, newText) : content.replaceFirst(
								java.util.regex.Pattern.quote(oldText), java.util.regex.Matcher.quoteReplacement(newText));
						return write(runId, path, updated).thenReturn("Edited " + path + " (" + (all ? count : 1)
								+ " replacement" + ((all ? count : 1) == 1 ? "" : "s") + ").");
					});
				});
	}

	AgentTool createFile() {
		return tool("create_file", """
				Create a new file with the given content (parent directories are created). Fails if the file \
				exists unless overwrite is true; prefer edit_file for changes to existing files.""", """
				{"type":"object","required":["path","content"],"properties":{
				"path":{"type":"string"},"content":{"type":"string"},
				"overwrite":{"type":"boolean","description":"Replace an existing file. Default false."}}}""", true,
				(runId, call) -> {
					String path = call.requiredString("path");
					String content = call.requiredString("content");
					Mono<Boolean> exists = read(runId, path).map(any -> true)
							.onErrorResume(ToolException.class, e -> Mono.just(!e.getMessage().startsWith("file not found")));
					return exists.flatMap(present -> {
						if (present && !call.bool("overwrite", false)) {
							throw new ToolException(path + " already exists; use edit_file, or set overwrite");
						}
						return write(runId, path, content).thenReturn((present ? "Overwrote " : "Created ") + path + ".");
					});
				});
	}

	AgentTool runCommand() {
		return tool("run_command", """
				Run a shell command in the repository root inside the sandbox (no credentials; network may be \
				restricted). Use it to build, run tests, or inspect the environment. Returns exit code and output.""", """
				{"type":"object","required":["command"],"properties":{
				"command":{"type":"string"},
				"timeout_seconds":{"type":"integer","description":"Default and maximum: the configured limit."}}}""", true,
				(runId, call) -> {
					long max = commandTimeout.toSeconds();
					long seconds = Math.clamp(call.integer("timeout_seconds", (int) Math.min(max, Integer.MAX_VALUE)), 1, max);
					return exec(runId, call.requiredString("command"), Duration.ofSeconds(seconds))
							.map(r -> (r.timedOut() ? "Timed out after " + seconds + "s" : "Exit code " + r.exitCode())
									+ "\n" + r.output());
				});
	}

	AgentTool diff() {
		return tool("show_diff", """
				Show all changes made so far as a unified diff against the base commit, including new and deleted \
				files.""", """
				{"type":"object","properties":{}}""", false,
				(runId, call) -> checkout.diff(runId).map(d -> d.isBlank() ? "No changes yet." : d));
	}

	private Mono<CommandResult> exec(UUID runId, String command, Duration timeout) {
		return sandbox.exec(runId, command, timeout);
	}

	private Mono<String> read(UUID runId, String rawPath) {
		String path = path(rawPath);
		return sandbox.readFile(runId, path, MAX_FILE_BYTES)
				.onErrorMap(NoSuchFileException.class, e -> new ToolException("file not found: " + path))
				.onErrorMap(IllegalArgumentException.class, e -> new ToolException(e.getMessage()));
	}

	private Mono<Void> write(UUID runId, String rawPath, String content) {
		return sandbox.writeFile(runId, path(rawPath), content)
				.onErrorMap(IllegalArgumentException.class, e -> new ToolException(e.getMessage()));
	}

	private static String path(String raw) {
		try {
			return WorkspacePath.relative(raw);
		}
		catch (IllegalArgumentException e) {
			throw new ToolException(e.getMessage());
		}
	}

	static int occurrences(String text, String needle) {
		int count = 0;
		for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}

	private static AgentTool tool(String name, String description, String schema, boolean mutates,
			BiFunction<UUID, ToolCall, Mono<String>> body) {
		ToolSpec spec = new ToolSpec(name, description, schema);
		return new AgentTool() {
			@Override
			public ToolSpec spec() {
				return spec;
			}

			@Override
			public Mono<String> execute(UUID runId, ToolCall call) {
				return Mono.defer(() -> body.apply(runId, call));
			}

			@Override
			public boolean mutates() {
				return mutates;
			}
		};
	}
}
