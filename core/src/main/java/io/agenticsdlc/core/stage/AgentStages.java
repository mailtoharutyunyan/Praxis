package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.agent.AgentLoop;
import io.agenticsdlc.core.agent.AgentModels;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.AgentTool;
import io.agenticsdlc.core.agent.tools.SandboxTools;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.engine.RunLimits;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.memory.MemoryRecall;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/**
 * The model-driven stages: triage, specification, implementation and review. Each runs an {@link AgentLoop} with
 * the role's model and tools, records its product as an {@code ARTIFACT_PRODUCED} event for the gates, and maps
 * loop limits to escalation so a human decides what happens next.
 */
public final class AgentStages {

	private static final Pattern RISK = Pattern.compile("RISK:\\s*(LOW|MEDIUM|HIGH)", Pattern.CASE_INSENSITIVE);
	private static final Pattern RATIONALE = Pattern.compile("RATIONALE:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SPEC_VERDICT = Pattern.compile("^[*_`\\s]*SPEC_VERDICT:\\s*(OK|REVISE)[*_`\\s]*$",
			Pattern.CASE_INSENSITIVE);
	/** Only the reply's last line counts, so a verdict quoted earlier (or planted in the diff) is ignored. */
	private static final Pattern VERDICT = Pattern.compile("^[*_`\\s]*VERDICT:\\s*(APPROVE|CHANGES_REQUESTED)[*_`\\s]*$",
			Pattern.CASE_INSENSITIVE);

	private final AgentModels models;
	private final RunWorkspace workspace;
	private final SandboxTools tools;
	private final RunLimits runLimits;
	private final AgentLoop.Limits loopLimits;
	private final Options options;

	/**
	 * Optional quality steps.
	 *
	 * @param testsFirst on a run's first implementation round, write failing tests for the change before
	 *        implementing it (test files only, checked to fail on the unchanged code), then hold the coder to them
	 * @param specCritic check each new specification with a fresh-context critic and let the planner revise it once
	 */
	public record Options(boolean testsFirst, boolean specCritic) {
		public static final Options NONE = new Options(false, false);
	}

	public AgentStages(AgentModels models, RunWorkspace workspace, SandboxTools tools, RunLimits runLimits,
			AgentLoop.Limits loopLimits) {
		this(models, workspace, tools, runLimits, loopLimits, Options.NONE);
	}

	/**
	 * Learned repository facts (see {@code core.memory}): recalled into each agent's brief, and the {@code remember}
	 * tool for agents that may store new ones. {@link #NONE} disables both.
	 */
	public record Memory(MemoryRecall recall, AgentTool remember) {
		public static final Memory NONE = new Memory(null, null);
	}

	private Memory memory = Memory.NONE;

	public AgentStages(AgentModels models, RunWorkspace workspace, SandboxTools tools, RunLimits runLimits,
			AgentLoop.Limits loopLimits, Options options, Memory memory) {
		this(models, workspace, tools, runLimits, loopLimits, options);
		this.memory = Objects.requireNonNull(memory, "memory");
	}

	public AgentStages(AgentModels models, RunWorkspace workspace, SandboxTools tools, RunLimits runLimits,
			AgentLoop.Limits loopLimits, Options options) {
		this.options = Objects.requireNonNull(options, "options");
		this.models = Objects.requireNonNull(models, "models");
		this.workspace = Objects.requireNonNull(workspace, "workspace");
		this.tools = Objects.requireNonNull(tools, "tools");
		this.runLimits = Objects.requireNonNull(runLimits, "runLimits");
		this.loopLimits = Objects.requireNonNull(loopLimits, "loopLimits");
	}

	public StageHandler handler(RunState stage) {
		return handlers().stream().filter(h -> h.stage() == stage).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("no agent stage for " + stage));
	}

	public List<StageHandler> handlers() {
		return List.of(stage(RunState.TRIAGING, this::triage), stage(RunState.SPECIFYING, this::specify),
				stage(RunState.IMPLEMENTING, this::implement), stage(RunState.REVIEWING, this::review));
	}

	Mono<StageOutcome> triage(StageContext context) {
		// One answer, no tools: triage reads only the request text.
		AgentLoop.Limits oneTurn = new AgentLoop.Limits(1, loopLimits.maxOutputTokens(), loopLimits.maxToolResultChars(),
				loopLimits.maxRepeats());
		return loop(AgentRole.TRIAGE, List.of(), oneTurn)
				.run(context, "agent:triage", Prompts.TRIAGE, Prompts.task(context.task()), remainingTokens(context))
				.map(outcome -> {
					Matcher rationale = RATIONALE.matcher(outcome.finalText());
					// Fail safe: an unreadable assessment gets every gate, and if several levels appear the highest wins.
					RiskLevel level = RISK.matcher(outcome.finalText()).results()
							.map(m -> RiskLevel.valueOf(m.group(1).toUpperCase(Locale.ROOT)))
							.max(java.util.Comparator.naturalOrder())
							.orElse(null);
					if (level == null) {
						return new StageOutcome.Triaged(RiskLevel.HIGH,
								"triage reply was not understood, defaulting to HIGH: " + abbreviate(outcome.finalText()),
								outcome.usage());
					}
					return new StageOutcome.Triaged(level, rationale.find() ? rationale.group(1).strip() : "",
							outcome.usage());
				});
	}

	/** What every agent stage starts from: the prepared workspace, the run's history and recalled repository facts. */
	private record Setup(RunWorkspace.Prepared prepared, RunHistory history, String memory) {
	}

	/** Recall reads the working copy, so it runs once the sandbox is up. */
	private Mono<Setup> setUp(StageContext context) {
		return workspace.prepare(context).flatMap(prepared -> Mono.zip(context.history().map(RunHistory::new),
				memoryNote(context)).map(t -> new Setup(prepared, t.getT1(), t.getT2())));
	}

	private Mono<String> memoryNote(StageContext context) {
		if (memory.recall() == null) {
			return Mono.just("");
		}
		return memory.recall().recall(context).map(facts -> facts.isEmpty() ? "" : "\n\nNotes learned in earlier runs on "
				+ "this repository, checked against the current code (verify before relying on them):\n"
				+ Prompts.block("repository_memory", String.join("\n", facts)));
	}

	/** The role's tools plus {@code remember}, when memory is on. */
	private List<AgentTool> withMemory(List<AgentTool> roleTools) {
		if (memory.remember() == null) {
			return roleTools;
		}
		List<AgentTool> all = new java.util.ArrayList<>(roleTools);
		all.add(memory.remember());
		return all;
	}

	Mono<StageOutcome> specify(StageContext context) {
		return setUp(context).flatMap(setup -> {
			RunWorkspace.Prepared prepared = setup.prepared();
			RunHistory history = setup.history();
			StringBuilder brief = new StringBuilder(Prompts.task(context.task()));
			appendRepository(brief, prepared);
			brief.append(setup.memory());
			history.latestChangeRequest().ifPresent(feedback -> {
				brief.append("\n\nA reviewer rejected the previous specification. Revise it.\n").append(feedback);
				history.latestArtifact(RunHistory.SPEC).ifPresent(previous -> brief.append(
						"\n\nPrevious specification:\n").append(Prompts.block("previous_spec", previous)));
			});
			return plan(context, brief.toString())
					.flatMap(outcome -> !outcome.completed() || !options.specCritic() ? Mono.just(outcome)
							: critique(context, brief.toString(), outcome))
					.flatMap(outcome -> !outcome.completed() ? Mono.just(escalate("planner", outcome))
							: artifact(context, RunHistory.SPEC, outcome.finalText(), Map.of())
									.thenReturn(completed(outcome, Map.of("specChars", outcome.finalText().length()))));
		});
	}

	private Mono<AgentLoop.Outcome> plan(StageContext context, String brief) {
		return loop(AgentRole.PLANNER, withMemory(tools.readOnlyTools()), loopLimits)
				.run(context, "agent:planner", Prompts.PLANNER, brief, remainingTokens(context));
	}

	/**
	 * A fresh-context critic checks the specification against the request and the repository. If it asks for a
	 * revision, the planner revises once with the findings. The critique is kept for the SPEC gate either way.
	 */
	private Mono<AgentLoop.Outcome> critique(StageContext context, String plannerBrief, AgentLoop.Outcome draft) {
		String brief = Prompts.task(context.task()) + "\n\nSpecification to check:\n" + Prompts.block("spec",
				draft.finalText());
		return loop(AgentRole.REVIEWER, tools.readOnlyTools(), loopLimits)
				.run(context, "agent:spec-critic", Prompts.SPEC_CRITIC, brief, remainingTokens(context))
				.flatMap(critique -> {
					Usage spent = draft.usage().plus(critique.usage());
					boolean revise = critique.completed() && SpecVerdict.REVISE == specVerdict(critique.finalText());
					String label = !critique.completed() ? "UNAVAILABLE" : revise ? "REVISE" : "OK";
					Mono<Void> recorded = artifact(context, RunHistory.SPEC_REVIEW,
							critique.completed() ? critique.finalText() : "The critic stopped: " + abbreviate(critique.finalText()),
							Map.of("verdict", label));
					if (!revise) {
						return recorded.thenReturn(withUsage(draft, spent));
					}
					String revision = plannerBrief + "\n\nYou wrote the specification below, and a reviewer found "
							+ "problems in it. Revise it to resolve them; reply with the complete revised specification only."
							+ "\n" + Prompts.block("previous_spec", draft.finalText())
							+ "\n" + Prompts.block("spec_review", critique.finalText());
					return recorded.then(plan(context, revision))
							.map(revised -> withUsage(revised, spent.plus(revised.usage())));
				});
	}

	enum SpecVerdict { OK, REVISE }

	/** Only the critique's last line counts, like the review verdict; anything unreadable counts as OK. */
	static SpecVerdict specVerdict(String critique) {
		String[] lines = critique.strip().split("\\R");
		Matcher verdict = SPEC_VERDICT.matcher(lines[lines.length - 1]);
		return verdict.matches() && verdict.group(1).equalsIgnoreCase("REVISE") ? SpecVerdict.REVISE : SpecVerdict.OK;
	}

	private static AgentLoop.Outcome withUsage(AgentLoop.Outcome outcome, Usage usage) {
		return new AgentLoop.Outcome(outcome.stop(), outcome.finalText(), usage, outcome.turns());
	}

	Mono<StageOutcome> implement(StageContext context) {
		return setUp(context).flatMap(setup -> {
			RunWorkspace.Prepared prepared = setup.prepared();
			RunHistory history = setup.history();
			boolean firstRound = history.latestArtifactEvent(RunHistory.TESTS).isEmpty()
					&& history.currentRevision().isEmpty() && history.latestRework().isEmpty();
			Mono<TestsFirst> tests = options.testsFirst() && firstRound
					? writeTests(context, prepared, history, setup.memory()) : Mono.just(TestsFirst.NONE);
			return tests.flatMap(written -> code(context, prepared, history, setup.memory(), written));
		});
	}

	private Mono<StageOutcome> code(StageContext context, RunWorkspace.Prepared prepared, RunHistory history,
			String memoryNote, TestsFirst written) {
		StringBuilder brief = new StringBuilder(Prompts.task(context.task()));
		history.latestArtifact(RunHistory.SPEC).ifPresent(spec -> brief.append(
				"\n\nApproved specification:\n").append(Prompts.block("spec", spec)));
		appendRepository(brief, prepared);
		brief.append(memoryNote);
		history.latestRework().ifPresent(rework -> brief.append("\n\nThis is a follow-up attempt. ")
				.append(rework).append("\nFix these problems; your earlier changes are still in the workspace."));
		history.latestChangeRequest().ifPresent(feedback -> brief.append("\n\n").append(feedback));
		history.currentRevision().ifPresent(revision -> brief.append(Prompts.revision(revision)));
		brief.append(written.briefForCoder());
		return loop(AgentRole.CODER, withMemory(tools.coderTools()), loopLimits)
				.run(context, "agent:coder", Prompts.CODER, brief.toString(), remainingTokens(context))
				.map(outcome -> new AgentLoop.Outcome(outcome.stop(), outcome.finalText(),
						outcome.usage().plus(written.usage()), outcome.turns()))
				.flatMap(outcome -> {
					if (!outcome.completed()) {
						return Mono.just(escalate("coder", outcome));
					}
					return workspace.diff(context).map(diff -> diff.isBlank()
							? (StageOutcome) new StageOutcome.Escalate("the coder finished without changing any "
									+ "file: " + abbreviate(outcome.finalText()), outcome.usage())
							: completed(outcome, Map.of("diffChars", diff.length())));
				});
	}

	/** Tests written before the implementation, as the coder needs to know them. */
	record TestsFirst(Usage usage, List<String> files, boolean failedFirst) {
		static final TestsFirst NONE = new TestsFirst(Usage.ZERO, List.of(), false);

		String briefForCoder() {
			if (files.isEmpty()) {
				return "";
			}
			return "\n\nTests for this change were written first" + (failedFirst ? " and fail on the current code" : "")
					+ ": " + String.join(", ", files) + ". Make them pass. Do not weaken or delete them; if one is "
					+ "genuinely wrong, fix it and say why in your final reply.";
		}
	}

	/** Write tests that fail now and pass once the change is made; one retry if they already pass. */
	private Mono<TestsFirst> writeTests(StageContext context, RunWorkspace.Prepared prepared, RunHistory history,
			String memoryNote) {
		StringBuilder brief = new StringBuilder(Prompts.task(context.task()));
		history.latestArtifact(RunHistory.SPEC).ifPresent(spec -> brief.append(
				"\n\nApproved specification:\n").append(Prompts.block("spec", spec)));
		appendRepository(brief, prepared);
		brief.append(memoryNote);
		return writeTests(context, prepared, brief.toString(), Usage.ZERO, 1);
	}

	private Mono<TestsFirst> writeTests(StageContext context, RunWorkspace.Prepared prepared, String brief, Usage spent,
			int attempt) {
		return loop(AgentRole.CODER, tools.testWriterTools(TestPaths::isTest), loopLimits)
				.run(context, "agent:test-writer", Prompts.TEST_WRITER, brief, remainingTokens(context))
				.flatMap(outcome -> {
					Usage usage = spent.plus(outcome.usage());
					if (!outcome.completed() || outcome.finalText().strip().startsWith("NO_TESTS")) {
						String reason = outcome.completed() ? outcome.finalText().strip()
								: "the test writer stopped: " + abbreviate(outcome.finalText());
						return testsArtifact(context, Map.of(), false, reason).thenReturn(new TestsFirst(usage, List.of(),
								false));
					}
					return workspace.diff(context).flatMap(diff -> {
						List<String> files = TestPaths.changedFiles(diff);
						if (files.isEmpty()) {
							return testsArtifact(context, Map.of(), false, "no tests were written")
									.thenReturn(new TestsFirst(usage, List.of(), false));
						}
						return workspace.verify(context, prepared, files).flatMap(results -> {
							var last = results.getLast();
							if (last.succeeded() && attempt == 1) {
								return writeTests(context, prepared, brief + "\n\nYour tests already pass on the "
										+ "current code, so they do not capture the change. Make them check the new "
										+ "behaviour. The test run said:\n" + Prompts.block("test_output", last.tail(4_000)),
										usage, 2);
							}
							return fingerprints(context, files).flatMap(prints -> testsArtifact(context, prints,
									!last.succeeded(), diff).thenReturn(new TestsFirst(usage, files, !last.succeeded())));
						});
					});
				});
	}

	private Mono<Map<String, Object>> fingerprints(StageContext context, List<String> files) {
		return reactor.core.publisher.Flux.fromIterable(files)
				.concatMap(file -> workspace.read(context, file)
						.map(content -> Map.entry(file, RunHistory.fingerprint(content)))
						.onErrorResume(e -> Mono.empty()))
				.collectMap(Map.Entry::getKey, entry -> (Object) entry.getValue(), LinkedHashMap::new);
	}

	private static Mono<Void> testsArtifact(StageContext context, Map<String, Object> files, boolean failedFirst,
			String content) {
		return artifact(context, RunHistory.TESTS, content, Map.of("files", files, "failedFirst", failedFirst));
	}

	/** For the reviewer: whether the tests written first were changed while implementing. */
	private Mono<String> testsNote(StageContext context, RunHistory history) {
		return history.latestArtifactEvent(RunHistory.TESTS)
				.filter(event -> event.payload().get("files") instanceof Map<?, ?> files && !files.isEmpty())
				.map(event -> {
					Map<?, ?> locked = (Map<?, ?>) event.payload().get("files");
					List<String> files = locked.keySet().stream().map(String::valueOf).toList();
					return fingerprints(context, files).map(current -> {
						List<String> changed = files.stream()
								.filter(file -> !String.valueOf(locked.get(file)).equals(current.get(file))).toList();
						if (changed.isEmpty()) {
							return "\n\nThese tests were written before the implementation and are unchanged: "
									+ String.join(", ", files) + ".";
						}
						return "\n\nThese tests were written before the implementation and were changed afterwards: "
								+ String.join(", ", changed) + ". Check in the diff that they were not weakened to make "
								+ "the implementation pass. As first written:\n"
								+ Prompts.block("tests_as_written", String.valueOf(event.payload().get("content")));
					});
				})
				.orElse(Mono.just(""));
	}

	Mono<StageOutcome> review(StageContext context) {
		return setUp(context).flatMap(setup -> workspace.diff(context).flatMap(diff -> testsNote(context, setup.history())
				.flatMap(testsNote -> {
					RunHistory history = setup.history();
					StringBuilder brief = new StringBuilder(Prompts.task(context.task()));
					history.latestArtifact(RunHistory.SPEC).ifPresent(spec -> brief.append(
							"\n\nSpecification:\n").append(Prompts.block("spec", spec)));
					appendRepository(brief, setup.prepared());
					brief.append(setup.memory());
					history.currentRevision().ifPresent(revision -> brief.append(Prompts.revision(revision))
							.append("\nCheck that the changes address this request."));
					brief.append(testsNote);
					history.latestArtifact(VerifyStage.SCAN).filter(scan -> !scan.startsWith("No findings")).ifPresent(scan ->
							brief.append("\n\nAutomated security scans of the changed files reported:\n")
									.append(Prompts.block("scan", scan)).append("\nWeigh these in your review."));
					brief.append("\n\nReview the current changes (show_diff).");
					return artifact(context, RunHistory.DIFF, diff, Map.of(RunHistory.FINGERPRINT, RunHistory.fingerprint(diff)))
							.then(loop(AgentRole.REVIEWER, withMemory(tools.readOnlyTools()), loopLimits)
									.run(context, "agent:reviewer", Prompts.REVIEWER, brief.toString(),
											remainingTokens(context)))
							.flatMap(outcome -> {
								if (!outcome.completed()) {
									return Mono.just(escalate("reviewer", outcome));
								}
								boolean approved = approved(outcome.finalText());
								String label = approved ? "APPROVE" : "CHANGES_REQUESTED";
								return artifact(context, RunHistory.REVIEW, outcome.finalText(), Map.of("verdict", label))
										.thenReturn(approved ? completed(outcome, Map.of("verdict", label))
												: new StageOutcome.NeedsRework(outcome.finalText(), outcome.usage()));
							});
				})));
	}

	/** The reviewer approves only with {@code VERDICT: APPROVE} as the last non-blank line of its reply. */
	static boolean approved(String reply) {
		String[] lines = reply.strip().split("\\R");
		Matcher verdict = VERDICT.matcher(lines[lines.length - 1]);
		return verdict.matches() && verdict.group(1).equalsIgnoreCase("APPROVE");
	}

	private AgentLoop loop(AgentRole role, List<AgentTool> roleTools, AgentLoop.Limits limits) {
		return new AgentLoop(models.forRole(role), roleTools, limits);
	}

	private long remainingTokens(StageContext context) {
		return Math.max(0, runLimits.maxTokens() - context.run().usage().totalTokens());
	}

	private static void appendRepository(StringBuilder brief, RunWorkspace.Prepared prepared) {
		var plan = prepared.plan();
		brief.append("\n\nRepository: base branch ").append(prepared.checkout().baseBranch()).append('.');
		if (plan.components().size() == 1 && plan.main().path().equals(".")) {
			var profile = plan.main().profile();
			brief.append(" Toolchain: ").append(profile.tool()).append(". Build: `").append(profile.build())
					.append("`. Tests: `").append(profile.test()).append("`.");
		}
		else {
			brief.append(" It holds several services; run_command takes service=<name> to run in that service's "
					+ "toolchain from its directory, and only the services you change are verified:");
			for (var component : plan.components()) {
				brief.append("\n- ").append(component.name()).append(" in ").append(component.path()).append(" (")
						.append(component.profile().tool()).append("; build `").append(component.profile().build())
						.append("`, tests `").append(component.profile().test()).append("`)");
			}
		}
		if (!plan.sidecars().isEmpty()) {
			brief.append("\nRunning beside the tests, reachable by host name: ")
					.append(String.join(", ", plan.sidecars().stream().map(s -> s.name() + " (" + s.image() + ")").toList()))
					.append(". Build environment: ").append(plan.env().keySet()).append('.');
		}
		if (prepared.checkout().agentInstructions() != null) {
			brief.append("\n\nRepository guidance (AGENTS.md / CLAUDE.md):\n")
					.append(Prompts.block("guidance", prepared.checkout().agentInstructions()));
		}
	}

	private static Mono<Void> artifact(StageContext context, String kind, String content, Map<String, Object> extra) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", kind);
		payload.putAll(extra);
		payload.put("content", content);
		return context.emit(RunEventType.ARTIFACT_PRODUCED, "system", payload);
	}

	private static StageOutcome completed(AgentLoop.Outcome outcome, Map<String, Object> summary) {
		Map<String, Object> merged = new LinkedHashMap<>(summary);
		merged.put("turns", outcome.turns());
		return new StageOutcome.Completed(outcome.usage(), merged);
	}

	private static StageOutcome escalate(String role, AgentLoop.Outcome outcome) {
		return new StageOutcome.Escalate(role + " stopped (" + outcome.stop() + "): " + abbreviate(outcome.finalText()),
				outcome.usage());
	}

	static String abbreviate(String text) {
		String flat = text == null ? "" : text.strip();
		return flat.length() <= 500 ? flat : flat.substring(0, 500) + "…";
	}

	private static StageHandler stage(RunState stage, Function<StageContext, Mono<StageOutcome>> body) {
		return new StageHandler() {
			@Override
			public RunState stage() {
				return stage;
			}

			@Override
			public Mono<StageOutcome> execute(StageContext context) {
				return Mono.defer(() -> body.apply(context));
			}
		};
	}
}
