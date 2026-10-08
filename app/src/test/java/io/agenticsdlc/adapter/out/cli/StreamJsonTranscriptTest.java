package io.agenticsdlc.adapter.out.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.agent.AgentLoop.Outcome;
import io.agenticsdlc.core.agent.AgentLoop.Stop;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.workspace.CommandResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StreamJsonTranscriptTest {

	private static final String SECRET = "sk-ant-oat01-transcript-secret";

	private final RecordingContext recording = new RecordingContext();
	private final JsonMapper json = JsonMapper.builder().build();

	private StreamJsonTranscript transcript(long budget) {
		return new StreamJsonTranscript(recording.context, "agent:coder", SECRET, budget, json);
	}

	private static List<String> lines() throws IOException {
		try (InputStream in = StreamJsonTranscriptTest.class.getResourceAsStream("/claude-code/transcript.jsonl")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}
	}

	private static void feed(StreamJsonTranscript transcript, List<String> lines) {
		lines.forEach(line -> transcript.accept(line).block());
	}

	@Test
	void aCrashReportsTheErrorLineNotTheBundledSourceAroundIt() {
		String crash = " 9 | // (c) Anthropic PBC.\n11 | import{Me}from\"/$bunfs/root/chunk-1.js\";import{V}from\"/$bunfs/root/c.js\"\n"
				+ "error: bubblewrap is required for subprocess env scrubbing and isolation.\n"
				+ "      at /$bunfs/root/chunk-2.js:1:2\n";
		assertThat(StreamJsonTranscript.errorLines(crash))
				.isEqualTo("error: bubblewrap is required for subprocess env scrubbing and isolation.");
		assertThat(StreamJsonTranscript.errorLines("plain failure")).isEqualTo("plain failure");
	}

	@Test
	void recordsWhatTheAgentLoopWouldAndTheResult() throws IOException {
		StreamJsonTranscript transcript = transcript(1_000_000);
		feed(transcript, lines());
		Outcome outcome = transcript.outcome();

		assertThat(outcome.stop()).isEqualTo(Stop.COMPLETED);
		assertThat(outcome.finalText()).isEqualTo("Done: hello.txt says hello agents.");
		assertThat(outcome.turns()).isEqualTo(4);
		// Output and cost from the result; input and cache tokens counted once per API message.
		assertThat(outcome.usage()).isEqualTo(new Usage(1320, 350, 12900, 3000, 42_100));
		assertThat(recording.context.spent()).isEqualTo(outcome.usage());

		assertThat(recording.events).extracting(e -> e.type()).containsExactly(RunEventType.AGENT_MESSAGE,
				RunEventType.TOOL_CALLED, RunEventType.TOOL_RESULT, RunEventType.TOOL_CALLED, RunEventType.TOOL_RESULT,
				RunEventType.TOOL_CALLED, RunEventType.TOOL_RESULT, RunEventType.AGENT_MESSAGE);
		assertThat(recording.events).allSatisfy(e -> assertThat(e.actor()).isEqualTo("agent:coder"));
		assertThat(recording.of(RunEventType.AGENT_MESSAGE).getFirst().payload()).containsEntry("text",
				"I'll read the file first.").containsEntry("model", "claude-code/claude-opus-5-5").containsEntry("turn", 1);
		assertThat(recording.of(RunEventType.AGENT_MESSAGE).getLast().payload()).containsEntry("turn", 4);
		assertThat(recording.of(RunEventType.TOOL_CALLED).getFirst().payload()).containsEntry("tool", "Read")
				.containsEntry("callId", "toolu_01")
				.containsEntry("arguments", "{\"file_path\":\"/workspace/hello.txt\"}");
		assertThat(recording.of(RunEventType.TOOL_RESULT)).extracting(e -> e.payload().get("tool"))
				.containsExactly("Read", "Bash", "Edit");
		assertThat(recording.of(RunEventType.TOOL_RESULT).getFirst().payload()).containsEntry("output",
				"     1\thello world\n").containsEntry("error", false);
		assertThat(recording.of(RunEventType.TOOL_RESULT).getLast().payload()).containsEntry("error", true);
	}

	@Test
	void theCredentialIsNeverRecorded() throws IOException {
		StreamJsonTranscript transcript = transcript(1_000_000);
		feed(transcript, lines());
		feed(transcript, List.of("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"num_turns\":1,"
				+ "\"result\":\"API error for key " + SECRET + "\"}"));

		assertThat(recording.events).allSatisfy(e -> assertThat(e.payload().toString()).doesNotContain(SECRET));
		assertThat(recording.of(RunEventType.TOOL_RESULT).get(1).payload()).containsEntry("output",
				StreamJsonTranscript.REDACTED);
		assertThat(transcript.outcome().finalText()).isEqualTo("API error for key [REDACTED]");
		assertThat(transcript.failed(new CommandResult("claude", 1, "auth failed for " + SECRET, false, false,
				Duration.ofSeconds(1))).finalText()).doesNotContain(SECRET);
	}

	@Test
	void resultSubtypesMapToTheLoopsStops() {
		assertThat(result("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"num_turns\":7}"))
				.satisfies(o -> {
					assertThat(o.stop()).isEqualTo(Stop.MAX_TURNS);
					assertThat(o.finalText()).isEqualTo("reached 7 turns");
				});
		assertThat(result("{\"type\":\"result\",\"subtype\":\"error_max_budget_usd\",\"is_error\":true,\"num_turns\":2,"
				+ "\"total_cost_usd\":1.5}")).satisfies(o -> {
					assertThat(o.stop()).isEqualTo(Stop.BUDGET_EXHAUSTED);
					assertThat(o.usage().costMicroUsd()).isEqualTo(1_500_000);
				});
		assertThat(result("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
				+ "\"errors\":[\"tool crashed\"]}").finalText()).isEqualTo(
						"Claude Code reported error_during_execution: tool crashed");
		assertThat(result("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Invalid API key\"}"))
				.satisfies(o -> assertThat(o.stop()).isEqualTo(Stop.FAILED));
	}

	private Outcome result(String line) {
		StreamJsonTranscript transcript = transcript(1_000);
		transcript.accept(line).block();
		return transcript.outcome();
	}

	@Test
	void withoutAResultTheExitExplainsTheFailure() {
		StreamJsonTranscript transcript = transcript(1_000);
		assertThat(transcript.outcome()).satisfies(o -> {
			assertThat(o.stop()).isEqualTo(Stop.FAILED);
			assertThat(o.finalText()).isEqualTo("Claude Code ended without a result");
		});
		assertThat(transcript.failed(new CommandResult("claude", 127, "sh: claude: not found", false, false,
				Duration.ofSeconds(1))).finalText()).contains("code 127", "musl", "not found");
		assertThat(transcript.failed(new CommandResult("claude", 137, "", false, true, Duration.ofSeconds(1200)))
				.finalText()).isEqualTo("Claude Code timed out after 1200s");
	}

	@Test
	void spendingOverTheBudgetIsNoticedAsItHappens() throws IOException {
		// The budget counts cache reads at a tenth: 4201 after the first message, 4201 + 50 + 4200 / 10 + 1 after the second.
		StreamJsonTranscript transcript = transcript(4_500);
		List<String> lines = lines();
		feed(transcript, lines.subList(0, 2));
		assertThat(transcript.overBudget()).isFalse();
		feed(transcript, lines.subList(2, 6));
		assertThat(transcript.overBudget()).isTrue();
		assertThat(transcript.outcome()).satisfies(o -> {
			assertThat(o.stop()).isEqualTo(Stop.BUDGET_EXHAUSTED);
			assertThat(o.usage().totalTokens()).isEqualTo(4201 + 4251);
		});
		assertThat(recording.context.spent().totalTokens()).isEqualTo(4201 + 4251);
	}
}
