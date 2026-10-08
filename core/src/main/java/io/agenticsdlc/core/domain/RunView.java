package io.agenticsdlc.core.domain;

import java.util.Objects;

/** A run together with the task it works on; what callers usually need to display or decide. */
public record RunView(Run run, Task task) {

	public RunView {
		Objects.requireNonNull(run, "run");
		Objects.requireNonNull(task, "task");
		if (!run.taskId().equals(task.id())) {
			throw new IllegalArgumentException("run " + run.id() + " does not belong to task " + task.id());
		}
	}
}
