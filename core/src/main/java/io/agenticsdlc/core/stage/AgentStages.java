package io.agenticsdlc.core.stage;

import io.agenticsdlc.core.agent.AgentLoop;
import io.agenticsdlc.core.agent.AgentModels;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.AgentTool;
import io.agenticsdlc.core.agent.tools.SandboxTools;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.engine.RunLimits;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
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
	private static final Pattern VERDICT = Pattern.compile("VERDICT:\\s*(APPROVE|CHANGES_REQUESTED)\\s*$",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

	private final AgentModels models;
	private final RunWorkspace workspace;
	private final SandboxTools tools;
	private final RunLimits runLimits;
	private final AgentLoop.Limits loopLimits;

	public AgentStages(AgentModels models, RunWorkspace workspace, SandboxTools tools, RunLimits runLimits,
			AgentLoop.Limits loopLimits) {
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
					Matcher risk = RISK.matcher(outcome.finalText());
					Matcher rationale = RATIONALE.matcher(outcome.finalText());
					if (!risk.find()) {
						// Fail safe: an unreadable assessment gets every gate.
						return new StageOutcome.Triaged(RiskLevel.HIGH,
								"triage reply was not understood, defaulting to HIGH: " + abbreviate(outcome.finalText()),
								outcome.usage());
					}
					return new StageOutcome.Triaged(RiskLevel.valueOf(risk.group(1).toUpperCase(Locale.ROOT)),
							rationale.find() ? rationale.group(1).strip() : "", outcome.usage());
				});
	}

	Mono<StageOutcome> specify(StageContext context) {
		return Mono.zip(workspace.prepare(context), context.history().map(RunHistory::new)).flatMap(tuple -> {
			RunWorkspace.Prepared prepared = tuple.getT1();
			RunHistory history = tuple.getT2();
			StringBuilder brief = new StringBuilder(Prompts.task(context.task()));
			appendRepository(brief, prepared);
			history.latestChangeRequest().ifPresent(feedback -> {
				brief.append("\n\nA reviewer rejected the previous specification. Revise it.\n").append(feedback);
				history.latestArtifact(RunHistory.SPEC).ifPresent(previous -> brief.append(
						"\n\nPrevious specification:\n<previous_spec>\n").append(previous).append("\n</previous_spec>"));
			});
			return loop(AgentRole.PLANNER, tools.readOnlyTools(), loopLimits)
					.run(context, "agent:planner", Prompts.PLANNER, brief.toString(), remainingTokens(context))
					.flatMap(outcome -> !outcome.completed() ? Mono.just(escalate("planner", outcome))
							: artifact(context, RunHistory.SPEC, outcome.finalText(), Map.of())
									.thenReturn(completed(outcome, Map.of("specChars", outcome.finalText().length()))));
		});
	}

	Mono<StageOutcome> implement(StageContext context) {
		return Mono.zip(workspace.prepare(context), context.history().map(RunHistory::new)).flatMap(tuple -> {
			RunWorkspace.Prepared prepared = tuple.getT1();
			RunHistory history = tuple.getT2();
			StringBuilder brief = new StringBuilder(Prompts.task(context.task()));
			history.latestArtifact(RunHistory.SPEC).ifPresent(spec -> brief.append(
					"\n\nApproved specification:\n<spec>\n").append(spec).append("\n</spec>"));
			appendRepository(brief, prepared);
			history.latestRework().ifPresent(rework -> brief.append("\n\nThis is a follow-up attempt. ")
					.append(rework).append("\nFix these problems; your earlier changes are still in the workspace."));
			history.latestChangeRequest().ifPresent(feedback -> brief.append("\n\n").append(feedback));
			return loop(AgentRole.CODER, tools.coderTools(), loopLimits)
					.run(context, "agent:coder", Prompts.CODER, brief.toString(), remainingTokens(context))
					.flatMap(outcome -> {
						if (!outcome.completed()) {
							return Mono.just(escalate("coder", outcome));
						}
						return workspace.diff(context).map(diff -> diff.isBlank()
								? (StageOutcome) new StageOutcome.Escalate("the coder finished without changing any "
										+ "file: " + abbreviate(outcome.finalText()), outcome.usage())
								: completed(outcome, Map.of("diffChars", diff.length())));
					});
		});
	}

	Mono<StageOutcome> review(StageContext context) {
		return Mono.zip(workspace.prepare(context), context.history().map(RunHistory::new), workspace.diff(context))
				.flatMap(tuple -> {
					RunHistory history = tuple.getT2();
					String diff = tuple.getT3();
					StringBuilder brief = new StringBuilder(Prompts.task(context.task()));
					history.latestArtifact(RunHistory.SPEC).ifPresent(spec -> brief.append(
							"\n\nSpecification:\n<spec>\n").append(spec).append("\n</spec>"));
					appendRepository(brief, tuple.getT1());
					brief.append("\n\nReview the current changes (show_diff).");
					return artifact(context, RunHistory.DIFF, diff, Map.of(RunHistory.FINGERPRINT, RunHistory.fingerprint(diff)))
							.then(loop(AgentRole.REVIEWER, tools.readOnlyTools(), loopLimits)
									.run(context, "agent:reviewer", Prompts.REVIEWER, brief.toString(),
											remainingTokens(context)))
							.flatMap(outcome -> {
								if (!outcome.completed()) {
									return Mono.just(escalate("reviewer", outcome));
								}
								Matcher verdict = VERDICT.matcher(outcome.finalText());
								boolean approved = verdict.find()
										&& verdict.group(1).equalsIgnoreCase("APPROVE");
								String label = approved ? "APPROVE" : "CHANGES_REQUESTED";
								return artifact(context, RunHistory.REVIEW, outcome.finalText(), Map.of("verdict", label))
										.thenReturn(approved ? completed(outcome, Map.of("verdict", label))
												: new StageOutcome.NeedsRework(outcome.finalText(), outcome.usage()));
							});
				});
	}

	private AgentLoop loop(AgentRole role, List<AgentTool> roleTools, AgentLoop.Limits limits) {
		return new AgentLoop(models.forRole(role), roleTools, limits);
	}

	private long remainingTokens(StageContext context) {
		return Math.max(0, runLimits.maxTokens() - context.run().usage().totalTokens());
	}

	private static void appendRepository(StringBuilder brief, RunWorkspace.Prepared prepared) {
		brief.append("\n\nRepository: base branch ").append(prepared.checkout().baseBranch())
				.append(". Toolchain: ").append(prepared.profile().tool())
				.append(". Build: `").append(prepared.profile().build())
				.append("`. Tests: `").append(prepared.profile().test()).append("`.");
		if (prepared.checkout().agentInstructions() != null) {
			brief.append("\n\nRepository guidance (AGENTS.md / CLAUDE.md):\n<guidance>\n")
					.append(prepared.checkout().agentInstructions()).append("\n</guidance>");
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
