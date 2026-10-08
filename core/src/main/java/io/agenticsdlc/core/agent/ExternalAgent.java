package io.agenticsdlc.core.agent;

import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.engine.StageContext;
import reactor.core.publisher.Mono;

/**
 * An agent engine other than the {@link AgentLoop}: an AI coding CLI, such as Claude Code, that runs its own loop
 * inside the run's sandbox (ADR-0008). For one stage call it does the job an {@link AgentLoop} would, records the
 * same run events and reports the same {@link AgentLoop.Outcome}, so stages check its work the same way.
 */
public interface ExternalAgent {

	/** What the agent may do in the working copy; each stage call asks for the least it needs. */
	enum Access {
		/** Nothing: it answers from the brief alone (triage). */
		NONE,
		/** Read and search the repository (planner, spec critic, reviewer). */
		READ_ONLY,
		/** Read anything; create and edit test files only; run nothing (test writer). */
		TESTS_ONLY,
		/** Read, edit and run commands (coder). */
		FULL
	}

	/** Which engine runs a task's agents. */
	sealed interface Choice {

		Choice LOOP = new Loop();
		Choice EXTERNAL = new External();

		/** The {@link AgentLoop} with the role's model and tools. */
		record Loop() implements Choice {
		}

		/** This external agent. */
		record External() implements Choice {
		}

		/** Neither may run the task; its stages escalate to a human with this reason. */
		record Unavailable(String reason) implements Choice {
		}
	}

	/** Decided per task when a stage starts: the engine is configured at runtime, and untrusted tasks may be kept off it. */
	Choice choose(Task task);

	/**
	 * Runs one agent call. Usage is recorded with {@link StageContext#recordSpend} as it arrives, and the call stops
	 * with {@link AgentLoop.Stop#BUDGET_EXHAUSTED} once it has spent more than {@code tokenBudget} tokens.
	 *
	 * @param actor event actor, e.g. {@code agent:coder}
	 * @param system the role's instructions
	 * @param brief the task, specification and feedback for this call
	 */
	Mono<AgentLoop.Outcome> run(StageContext context, AgentRole role, Access access, String actor, String system,
			String brief, long tokenBudget);

	/**
	 * {@link #run(StageContext, AgentRole, Access, String, String, String, long)} in the named sandbox environment,
	 * the one with the toolchain of what the call works on.
	 *
	 * @param remember the repository memory's {@code remember} tool, for facts the agent learns; null when the role
	 *        may not store facts or memory is off. The engine collects its agent's facts and stores each through it,
	 *        so they are checked exactly as in the {@link AgentLoop}.
	 */
	default Mono<AgentLoop.Outcome> run(StageContext context, AgentRole role, Access access, String actor, String system,
			String brief, long tokenBudget, String environment, AgentTool remember) {
		return run(context, role, access, actor, system, brief, tokenBudget);
	}

	/** No external engine: every agent runs in the {@link AgentLoop}. */
	ExternalAgent NONE = new ExternalAgent() {
		@Override
		public Choice choose(Task task) {
			return Choice.LOOP;
		}

		@Override
		public Mono<AgentLoop.Outcome> run(StageContext context, AgentRole role, Access access, String actor,
				String system, String brief, long tokenBudget) {
			return Mono.error(new IllegalStateException("no external agent is configured"));
		}
	};
}
