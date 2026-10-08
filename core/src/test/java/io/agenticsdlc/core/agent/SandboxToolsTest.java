package io.agenticsdlc.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.agent.tools.SandboxTools;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.support.MemorySandbox;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import io.agenticsdlc.core.workspace.WorkspacePath;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

class SandboxToolsTest {

	private final MemorySandbox sandbox = new MemorySandbox();
	private final SandboxTools tools = new SandboxTools(sandbox, new RepositoryCheckout() {
		@Override
		public Mono<CheckoutInfo> checkout(RunView view) {
			return Mono.empty();
		}

		@Override
		public Mono<String> diff(UUID runId) {
			return Mono.just("");
		}

		@Override
		public Mono<Void> remove(UUID runId) {
			return Mono.empty();
		}
	}, Duration.ofSeconds(60));
	private final UUID run = UUID.randomUUID();

	private String call(AgentTool tool, Map<String, Object> args) {
		return tool.execute(run, new ToolCall("1", tool.spec().name(), args, args.toString())).block();
	}

	private AgentTool named(String name) {
		return tools.coderTools().stream().filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
	}

	@ParameterizedTest
	@CsvSource({ "src/App.java,src/App.java", "/workspace/src/App.java,src/App.java", "./a/../b.txt,b.txt",
			"'',.", "/workspace,." })
	void acceptsWorkspacePaths(String input, String expected) {
		assertThat(WorkspacePath.relative(input)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = { "../secret", "/etc/passwd", "a/../../b", "/workspace/../etc" })
	void rejectsEscapes(String input) {
		assertThatThrownBy(() -> WorkspacePath.relative(input)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shellQuoting() {
		assertThat(WorkspacePath.shellQuote("it's")).isEqualTo("'it'\"'\"'s'");
	}

	@Test
	void viewShowsRangesWithLineNumbers() {
		sandbox.files.put("f.txt", "a\nb\nc\nd\n");
		assertThat(call(named("view_file"), Map.of("path", "f.txt", "start_line", 2, "end_line", 3)))
				.isEqualTo(String.format("%6d\tb%n%6d\tc%n", 2, 3) + "… 1 more lines; use start_line=4\n");
		sandbox.files.put("empty.txt", "");
		assertThat(call(named("view_file"), Map.of("path", "empty.txt"))).isEqualTo("(empty file)");
		assertThatThrownBy(() -> call(named("view_file"), Map.of("path", "f.txt", "start_line", 9)))
				.isInstanceOf(ToolException.class);
		assertThatThrownBy(() -> call(named("view_file"), Map.of("path", "nope.txt")))
				.hasMessageContaining("file not found: nope.txt");
		assertThatThrownBy(() -> call(named("view_file"), Map.of())).hasMessageContaining("missing required argument");
	}

	@Test
	void editRequiresUniqueExactMatchUnlessReplaceAll() {
		sandbox.files.put("f.txt", "x = 1\nx = 1\n");
		assertThatThrownBy(() -> call(named("edit_file"), Map.of("path", "f.txt", "old_string", "x = 1", "new_string",
				"x = 2"))).hasMessageContaining("occurs 2 times");
		assertThat(call(named("edit_file"), Map.of("path", "f.txt", "old_string", "x = 1", "new_string", "x = $2",
				"replace_all", true))).isEqualTo("Edited f.txt (2 replacements).");
		assertThat(sandbox.files.get("f.txt")).isEqualTo("x = $2\nx = $2\n");
		assertThatThrownBy(() -> call(named("edit_file"), Map.of("path", "f.txt", "old_string", "", "new_string", "y")))
				.hasMessageContaining("must not be empty");
		assertThatThrownBy(() -> call(named("edit_file"), Map.of("path", "f.txt", "old_string", "x", "new_string", "x")))
				.hasMessageContaining("identical");
		sandbox.files.put("g.txt", "price = $1\n");
		assertThat(call(named("edit_file"), Map.of("path", "g.txt", "old_string", "$1", "new_string", "$2")))
				.isEqualTo("Edited g.txt (1 replacement).");
		assertThat(sandbox.files.get("g.txt")).isEqualTo("price = $2\n");
	}

	@Test
	void createRefusesToClobberUnlessAsked() {
		assertThat(call(named("create_file"), Map.of("path", "new/Hello.java", "content", "class Hello {}")))
				.isEqualTo("Created new/Hello.java.");
		assertThatThrownBy(() -> call(named("create_file"), Map.of("path", "new/Hello.java", "content", "x")))
				.hasMessageContaining("already exists");
		assertThat(call(named("create_file"), Map.of("path", "new/Hello.java", "content", "x", "overwrite", true)))
				.isEqualTo("Overwrote new/Hello.java.");
	}

	@Test
	void commandsAndSearchAreQuotedAndBounded() {
		sandbox.onExec = c -> new CommandResult(c, 1, "boom", false, false, Duration.ZERO);
		assertThat(call(named("run_command"), Map.of("command", "make test", "timeout_seconds", 9999)))
				.isEqualTo("Exit code 1\nboom");
		sandbox.onExec = c -> new CommandResult(c, 1, "", false, false, Duration.ZERO);
		assertThat(call(named("search"), Map.of("pattern", "it's"))).isEqualTo("No matches.");
		assertThat(sandbox.commands.getLast()).contains("-e 'it'\"'\"'s'").contains("-- '.'");
		assertThat(call(named("list_files"), Map.of("max_depth", 50))).isEqualTo("(empty)");
		assertThat(sandbox.commands.getLast()).contains("-maxdepth 6");
		assertThat(call(named("show_diff"), Map.of())).isEqualTo("No changes yet.");
	}

	@Test
	void reviewerToolsAreReadOnly() {
		assertThat(tools.readOnlyTools()).noneMatch(AgentTool::mutates);
		assertThat(tools.coderTools()).anyMatch(AgentTool::mutates);
	}
}
