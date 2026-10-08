package io.agenticsdlc.core.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.workspace.BuildPlan;
import io.agenticsdlc.core.workspace.BuildProfile;
import io.agenticsdlc.core.workspace.SandboxSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An agent with a shell runs where it can build what it changes: the service its brief is about. */
class RunWorkspaceEnvironmentTest {

	private static final BuildPlan PLAN = new BuildPlan(List.of(
			new BuildPlan.Component("java", ".", new BuildProfile("maven", "maven:3.9-eclipse-temurin-25", null, "b", "t")),
			new BuildPlan.Component("ui", "ui", new BuildProfile("npm", "node:24-bookworm", "npm ci", "b", "t"))),
			List.of(), Map.of());

	@Test
	void aBriefAboutTheUiRunsInTheUiEnvironment() {
		assertThat(RunWorkspace.environmentFor(PLAN, "Change ui/src/pages/RunsPage.tsx and test it in ui/src/pages/x.test.tsx"))
				.isEqualTo("ui");
	}

	@Test
	void anythingElseRunsInTheMainEnvironment() {
		assertThat(RunWorkspace.environmentFor(PLAN, "Change core/src/main/java/Foo.java")).isEqualTo(SandboxSpec.MAIN);
		// "ui/" inside another path segment does not count.
		assertThat(RunWorkspace.environmentFor(PLAN, "Change tui/src/x.ts and gui/y.ts")).isEqualTo(SandboxSpec.MAIN);
		assertThat(RunWorkspace.environmentFor((BuildPlan) null, "ui/src/x.ts")).isEqualTo(SandboxSpec.MAIN);
	}
}
