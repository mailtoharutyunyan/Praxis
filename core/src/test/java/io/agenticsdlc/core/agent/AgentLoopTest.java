package io.agenticsdlc.core.agent;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.agent.tools.SandboxTools;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import io.agenticsdlc.core.support.MemorySandbox;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentLoopTest {

	private static final AgentLoop.Limits LIMITS = new AgentLoop.Limits(10, 1024, 500, 3);

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final MemorySandbox sandbox = new MemorySandbox();
	private final RepositoryCheckout checkout = new RepositoryCheckout() {
		@Override
		public Mono<CheckoutInfo> checkout(RunView view) {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Mono<String> diff(UUID runId) {
			return Mono.just("--- a/x\n+++ b/x\n");
		}

		@Override
		public Mono<Void> remove(UUID runId) {
			return Mono.empty();
		}
	};
	private final SandboxTools tools = new SandboxTools(sandbox, checkout, Duration.ofMinutes(5));
	private StageContext context;

	@BeforeEach
	void setUp() {
		RunView view = new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES)
				.submit(Fixtures.prompt("alice")).block().view();
		store.claim("w", Duration.ofSeconds(30)).block();
		context = new StageContext(view, store, "w", CLOCK);
		sandbox.files.put("src/App.java", "class App {\n  int x = 1;\n}\n");
	}

	private List<RunEvent> events() {
		return store.allEvents(context.run().id());
	}

	@Test
	void runsToolsUntilTheModelAnswersAndRecordsEverything() {
		ScriptedModel model = new ScriptedModel()
				.thenCall("view_file", Map.of("path", "src/App.java"))
				.thenCall("edit_file", Map.of("path", "src/App.java", "old_string", "int x = 1;", "new_string", "int x = 2;"))
				.thenAnswer("Changed x to 2.");

		AgentLoop.Outcome outcome = new AgentLoop(model, tools.coderTools(), LIMITS)
				.run(context, "agent:coder", "You are a coder.", "Set x to 2", 100_000).block();

		assertThat(outcome.stop()).isEqualTo(AgentLoop.Stop.COMPLETED);
		assertThat(outcome.finalText()).isEqualTo("Changed x to 2.");
		assertThat(outcome.turns()).isEqualTo(3);
		assertThat(outcome.usage()).isEqualTo(new Usage(250, 40, 0, 0, 25));
		assertThat(sandbox.files.get("src/App.java")).contains("int x = 2;");

		assertThat(events()).extracting(RunEvent::type).containsSubsequence(RunEventType.TOOL_CALLED,
				RunEventType.TOOL_RESULT, RunEventType.TOOL_CALLED, RunEventType.TOOL_RESULT, RunEventType.AGENT_MESSAGE);
		ModelRequest third = model.requests.get(2);
		assertThat(third.system()).isEqualTo("You are a coder.");
		assertThat(third.messages()).hasSize(5);
		assertThat(third.tools()).extracting(ToolSpec::name).contains("view_file", "edit_file", "run_command");
		AgentMessage.ToolResults viewResult = (AgentMessage.ToolResults) third.messages().get(2);
		assertThat(viewResult.results().getFirst().content()).contains("     2\t  int x = 1;");
	}

	@Test
	void toolErrorsGoBackToTheModel() {
		ScriptedModel model = new ScriptedModel()
				.thenCall("edit_file", Map.of("path", "src/App.java", "old_string", "missing", "new_string", "y"))
				.thenCall("view_file", Map.of("path", "../../etc/passwd"))
				.thenCall("teleport", Map.of())
				.thenAnswer("gave up");
		AgentLoop.Outcome outcome = new AgentLoop(model, tools.coderTools(), LIMITS)
				.run(context, "agent:coder", "s", "t", 100_000).block();

		assertThat(outcome.completed()).isTrue();
		List<String> errors = new ArrayList<>();
		for (ModelRequest request : model.requests.subList(1, 4)) {
			AgentMessage.ToolResults results = (AgentMessage.ToolResults) request.messages().getLast();
			assertThat(results.results().getFirst().error()).isTrue();
			errors.add(results.results().getFirst().content());
		}
		assertThat(errors.get(0)).contains("old_string not found");
		assertThat(errors.get(1)).contains("escapes the workspace");
		assertThat(errors.get(2)).contains("unknown tool 'teleport'");
	}

	@Test
	void stopsWhenStuckRepeatingTheSameCall() {
		ScriptedModel model = new ScriptedModel().always(new ModelReply("", List.of(new ToolCall("c", "list_files",
				Map.of(), "{}")), Usage.ZERO, "tool_use"));
		AgentLoop.Outcome outcome = new AgentLoop(model, tools.coderTools(), LIMITS)
				.run(context, "agent:coder", "s", "t", 100_000).block();
		assertThat(outcome.stop()).isEqualTo(AgentLoop.Stop.STUCK);
		assertThat(outcome.turns()).isEqualTo(3);
	}

	@Test
	void stopsAtMaxTurnsAndBudget() {
		ScriptedModel looping = new ScriptedModel();
		for (int i = 0; i < 20; i++) {
			looping.thenCall("view_file", Map.of("path", "src/App.java", "start_line", i + 1));
		}
		assertThat(new AgentLoop(looping, tools.coderTools(), new AgentLoop.Limits(4, 1024, 500, 3))
				.run(context, "a", "s", "t", 100_000).block().stop()).isEqualTo(AgentLoop.Stop.MAX_TURNS);

		ScriptedModel expensive = new ScriptedModel().thenCall("list_files", Map.of()).thenAnswer("never reached");
		AgentLoop.Outcome broke = new AgentLoop(expensive, tools.coderTools(), LIMITS)
				.run(context, "a", "s", "t", 50).block();
		assertThat(broke.stop()).isEqualTo(AgentLoop.Stop.BUDGET_EXHAUSTED);
	}

	@Test
	void longToolOutputIsTruncatedForTheModel() {
		sandbox.onExec = c -> new io.agenticsdlc.core.workspace.CommandResult(c, 0, "x".repeat(5_000) + "END", false,
				false, Duration.ZERO);
		ScriptedModel model = new ScriptedModel().thenCall("run_command", Map.of("command", "make")).thenAnswer("done");
		new AgentLoop(model, tools.coderTools(), LIMITS).run(context, "a", "s", "t", 100_000).block();
		String content = ((AgentMessage.ToolResults) model.requests.get(1).messages().getLast()).results().getFirst()
				.content();
		assertThat(content).startsWith("[output truncated").endsWith("END").hasSizeLessThan(600);
	}

	@Test
	void oldToolResultsAreClearedButRecentOnesKept() {
		List<AgentMessage> messages = new ArrayList<>();
		messages.add(new AgentMessage.User("task"));
		for (int i = 0; i < 10; i++) {
			messages.add(new AgentMessage.Assistant("", List.of()));
			messages.add(new AgentMessage.ToolResults(List.of(new ToolResult("c" + i, "view_file", "y".repeat(1_000), false))));
		}
		List<AgentMessage> compacted = AgentLoop.compact(messages);
		List<String> contents = compacted.stream().filter(AgentMessage.ToolResults.class::isInstance)
				.map(m -> ((AgentMessage.ToolResults) m).results().getFirst().content()).toList();
		assertThat(contents.subList(0, 4)).allMatch(c -> c.contains("older output cleared"));
		assertThat(contents.subList(4, 10)).allMatch(c -> c.length() == 1_000);
	}

	@Test
	void validatesSetup() {
		assertThatThrownBy(() -> new AgentLoop.Limits(0, 1, 100, 2)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AgentLoop(new ScriptedModel(), List.of(tools.readOnlyTools().getFirst(), tools.readOnlyTools().getFirst()), LIMITS))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ToolSpec("Bad Name", "d", "{}")).isInstanceOf(IllegalArgumentException.class);
	}
}
