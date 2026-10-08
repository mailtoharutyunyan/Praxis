package io.agenticsdlc.core.application;

import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import java.util.List;
import java.util.Map;

/**
 * How far a run has come, for progress bars: a percentage, the phase (step {@code step} of {@code steps}), and what is
 * happening right now. Each phase owns a slice of 0-100 %; within a working phase the slice fills as the agent takes
 * turns or commands finish. Waiting for a human holds the bar where the run stopped. A fix iteration or a revision
 * sends the run back to implementation, so the bar can move back: it shows where the run is, not effort spent.
 *
 * @param waiting a human must act (gate, escalation)
 * @param finished the run reached DONE, FAILED or CANCELLED
 */
public record RunProgress(int percent, String phase, int step, int steps, String activity, boolean waiting,
		boolean finished) {

	private record Phase(String name, int from, int to, int expectedWork) {
	}

	/** Phases in order, their share of the bar, and how many agent turns or commands they usually take. */
	private static final Map<RunState, Phase> PHASES = Map.of(
			RunState.RECEIVED, new Phase("Queued", 0, 2, 1),
			RunState.TRIAGING, new Phase("Triage", 2, 6, 1),
			RunState.PREPARING_CONTEXT, new Phase("Preparing the workspace", 6, 15, 4),
			RunState.SPECIFYING, new Phase("Writing the specification", 15, 30, 12),
			RunState.IMPLEMENTING, new Phase("Implementing", 30, 65, 30),
			RunState.VERIFYING, new Phase("Building and testing", 65, 75, 3),
			RunState.REVIEWING, new Phase("Reviewing", 75, 87, 10),
			RunState.PUBLISHING, new Phase("Publishing", 87, 95, 3));
	private static final List<RunState> ORDER = List.of(RunState.TRIAGING, RunState.PREPARING_CONTEXT,
			RunState.SPECIFYING, RunState.IMPLEMENTING, RunState.VERIFYING, RunState.REVIEWING, RunState.PUBLISHING,
			RunState.PR_OPEN, RunState.DONE);

	/** From the run's state alone, e.g. for lists. */
	public static RunProgress of(Run run) {
		return of(run, List.of());
	}

	/** @param events the run's recent events, oldest first (state changes, agent messages, tool calls, commands) */
	public static RunProgress of(Run run, List<RunEvent> events) {
		RunState state = run.state();
		int steps = ORDER.size();
		switch (state) {
			case DONE:
				return new RunProgress(100, "Done: pull request merged", steps, steps, "Finished", false, true);
			case PR_OPEN:
				return new RunProgress(97, "Pull request open", ORDER.indexOf(RunState.PR_OPEN) + 1, steps,
						"Waiting for reviewers to merge the pull request", false, false);
			case FAILED, CANCELLED: {
				RunState last = lastWorkingState(events);
				int at = last == null ? 0 : PHASES.get(last).from();
				return new RunProgress(at, state == RunState.FAILED ? "Failed" : "Cancelled", step(last), steps,
						state == RunState.FAILED ? "The run failed" : "The run was cancelled", false, true);
			}
			case AWAITING_APPROVAL: {
				RunState before = switch (run.pendingGate()) {
					case SPEC -> RunState.SPECIFYING;
					case IMPLEMENTATION -> RunState.VERIFYING;
					case PUBLISH -> RunState.REVIEWING;
				};
				return new RunProgress(PHASES.get(before).to(), "Waiting for approval: " + run.pendingGate() + " gate",
						step(before), steps, "A human decides at the " + run.pendingGate() + " gate", true, false);
			}
			case NEEDS_HUMAN: {
				RunState at = run.resumeState();
				return new RunProgress(PHASES.get(at).from(), "Needs a human", step(at), steps,
						"Stopped at " + PHASES.get(at).name().toLowerCase(java.util.Locale.ROOT)
								+ "; see the latest error, then resume or cancel", true, false);
			}
			default: {
				Phase phase = PHASES.get(state);
				List<RunEvent> current = sinceEntering(state, events);
				long work = current.stream().filter(e -> e.type() == RunEventType.AGENT_MESSAGE
						|| e.type() == RunEventType.TOOL_CALLED || e.type() == RunEventType.COMMAND_OUTPUT).count();
				double done = Math.min(0.95, (double) work / (work + phase.expectedWork()));
				int percent = phase.from() + (int) Math.round((phase.to() - phase.from()) * done);
				String activity = current.isEmpty() ? "Starting" : describe(current.getLast());
				if (state == RunState.IMPLEMENTING && run.fixIterations() > 0) {
					activity += " (fix attempt " + run.fixIterations() + ")";
				}
				return new RunProgress(percent, phase.name(), step(state), steps, activity, false, false);
			}
		}
	}

	private static int step(RunState state) {
		int index = ORDER.indexOf(state);
		return index < 0 ? 1 : index + 1;
	}

	private static RunState lastWorkingState(List<RunEvent> events) {
		for (RunEvent event : events.reversed()) {
			if (event.type() == RunEventType.STATE_CHANGED) {
				try {
					RunState from = RunState.valueOf(String.valueOf(event.payload().get("from")));
					if (PHASES.containsKey(from)) {
						return from;
					}
				}
				catch (IllegalArgumentException e) {
					// not a state name
				}
			}
		}
		return null;
	}

	private static List<RunEvent> sinceEntering(RunState state, List<RunEvent> events) {
		for (int i = events.size() - 1; i >= 0; i--) {
			RunEvent event = events.get(i);
			if (event.type() == RunEventType.STATE_CHANGED && state.name().equals(event.payload().get("to"))) {
				return events.subList(i + 1, events.size());
			}
		}
		return events;
	}

	/** A short line for what the event shows the run doing. */
	static String describe(RunEvent event) {
		Map<String, Object> p = event.payload();
		String who = event.actor().replaceFirst("^agent:", "");
		String text = switch (event.type()) {
			case TOOL_CALLED -> who + ": " + p.get("tool") + " " + abbreviate(String.valueOf(p.getOrDefault("arguments", "")), 80);
			case AGENT_MESSAGE -> who + ": " + abbreviate(String.valueOf(p.getOrDefault("text", p.getOrDefault("message", ""))), 100);
			case COMMAND_OUTPUT -> "ran `" + abbreviate(String.valueOf(p.get("command")), 80) + "` → "
					+ (Boolean.TRUE.equals(p.get("timedOut")) ? "timed out" : "exit " + p.get("exitCode"))
					+ (p.get("service") == null ? "" : " (" + p.get("service") + ")");
			default -> event.type().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
		};
		return text.strip();
	}

	private static String abbreviate(String text, int max) {
		String line = text.replaceAll("\\s+", " ").strip();
		return line.length() <= max ? line : line.substring(0, max) + "…";
	}
}
