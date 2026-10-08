package io.agenticsdlc;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.core.agent.AgentModel;
import io.agenticsdlc.core.agent.AgentModels;
import io.agenticsdlc.core.agent.AgentRole;
import io.agenticsdlc.core.agent.ModelReply;
import io.agenticsdlc.core.agent.ModelRequest;
import io.agenticsdlc.core.agent.ToolCall;
import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Usage;
import io.agenticsdlc.core.scm.PullRequestTracker;
import io.agenticsdlc.support.FakeScmServer;
import io.agenticsdlc.support.TestRepos;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * M5 end to end: a LOW run passes the PUBLISH gate, the real publish stage pushes to the "remote" (a local bare
 * repository) and opens a pull request on a fake GitHub API; when GitHub reports it merged, the run is DONE.
 */
@Import({ TestcontainersConfiguration.class, PublishPipelineTest.Models.class })
@SpringBootTest(properties = { "agentic.sandbox.enabled=true", "agentic.agent.enabled=true",
		"agentic.stub-stages.enabled=false", "agentic.sandbox.network=none", "agentic.sandbox.memory=256MB",
		"agentic.worker.poll-interval=50ms", "agentic.scm.tokens[github.com]=test-token",
		"agentic.scm.pull-request-poll-interval=1h" })
class PublishPipelineTest {

	private static final String REPO = "https://github.com/acme/publish-demo.git";
	private static final Path ROOT;
	private static final Path REMOTE;
	private static final FakeScmServer GITHUB;
	private static final AtomicReference<String> PR_STATE = new AtomicReference<>("open");

	static {
		try {
			ROOT = Files.createTempDirectory("agentic-publish");
			Path source = TestRepos.demoRepo(ROOT.resolve("source"));
			REMOTE = ROOT.resolve("remote.git");
			try (Git git = Git.cloneRepository().setURI(source.toUri().toString()).setBare(true)
					.setDirectory(REMOTE.toFile()).call()) {
				// the provider's copy of the repository
			}
			GITHUB = new FakeScmServer()
					.on("GET", "/repos/acme/publish-demo/pulls/5", r -> new FakeScmServer.Response(200,
							"{\"number\":5,\"state\":\"" + (PR_STATE.get().equals("merged") ? "closed" : "open")
									+ "\",\"merged\":" + PR_STATE.get().equals("merged") + "}"))
					.on("GET", "/repos/acme/publish-demo/pulls", r -> new FakeScmServer.Response(200, "[]"))
					.on("POST", "/repos/acme/publish-demo/pulls", r -> new FakeScmServer.Response(201,
							"{\"number\":5,\"html_url\":\"https://github.com/acme/publish-demo/pull/5\"}"));
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@AfterAll
	static void stopServer() {
		GITHUB.close();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.sandbox.workspace-root", () -> ROOT.resolve("workspaces").toString());
		registry.add("agentic.scm.mirrors[" + REPO + "]", () -> REMOTE.toUri().toString());
		registry.add("agentic.scm.api-urls[github.com]", GITHUB::url);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Models {
		@Bean
		@Primary
		AgentModels publishScriptedModels() {
			Map<AgentRole, List<ModelReply>> scripts = Map.of(
					AgentRole.TRIAGE, List.of(answer("RISK: LOW\nRATIONALE: one line of text")),
					AgentRole.PLANNER, List.of(answer("## Requirements\n1. WHEN read THE SYSTEM SHALL greet agents.")),
					AgentRole.CODER, List.of(call("edit_file", Map.of("path", "hello.txt", "old_string", "hello world",
							"new_string", "hello agent")), answer("Done.")),
					AgentRole.REVIEWER, List.of(answer("Fine.\nVERDICT: APPROVE")));
			Map<AgentRole, java.util.concurrent.atomic.AtomicInteger> positions = new java.util.concurrent.ConcurrentHashMap<>();
			return role -> new AgentModel() {
				@Override
				public String id() {
					return "scripted/" + role;
				}

				@Override
				public Mono<ModelReply> complete(ModelRequest request) {
					int index = positions.computeIfAbsent(role, r -> new java.util.concurrent.atomic.AtomicInteger())
							.getAndIncrement();
					List<ModelReply> script = scripts.get(role);
					return index < script.size() ? Mono.just(script.get(index))
							: Mono.error(new IllegalStateException("script exhausted for " + role));
				}
			};
		}

		private static ModelReply call(String tool, Map<String, Object> args) {
			return new ModelReply("", List.of(new ToolCall("c-" + tool, tool, args, args.toString())), Usage.ZERO, "tool_use");
		}

		private static ModelReply answer(String text) {
			return new ModelReply(text, List.of(), Usage.ZERO, "end_turn");
		}
	}

	@Autowired
	TaskIntake intake;

	@Autowired
	RunQueries queries;

	@Autowired
	RunCommands commands;

	@Autowired
	PullRequestTracker tracker;

	private Run await(UUID id, Predicate<Run> condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 120_000;
		Run run = queries.get(id).block().run();
		while (!condition.test(run)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out in " + run.state() + "; events: "
						+ queries.events(id, 0, 500).collectList().block());
			}
			Thread.sleep(100);
			run = queries.get(id).block().run();
		}
		return run;
	}

	@Test
	void approvedRunIsPushedOpensAPullRequestAndFinishesWhenMerged() throws Exception {
		UUID id = intake.submit(new NewTask(TaskOrigin.PROMPT, null, "Greet agents", "hello agent",
				new RepositoryRef(ScmKind.GITHUB, URI.create(REPO)), null, "alice", null)).block().view().run().id();

		Run atPublish = await(id, r -> r.pendingGate() == Gate.PUBLISH || r.state() == RunState.NEEDS_HUMAN
				|| r.state().isTerminal());
		assertThat(atPublish.pendingGate()).as("state %s", atPublish.state()).isEqualTo(Gate.PUBLISH);
		try (Repository remote = new FileRepositoryBuilder().setGitDir(REMOTE.toFile()).build()) {
			assertThat(remote.resolve("refs/heads/agent/" + id)).as("nothing is pushed before approval").isNull();
		}

		commands.decide(id, Gate.PUBLISH, GateDecision.APPROVE, "ship it", "bob").block();
		Run open = await(id, r -> r.state() == RunState.PR_OPEN || r.state() == RunState.NEEDS_HUMAN
				|| r.state().isTerminal());
		assertThat(open.state()).as("events: %s", queries.events(id, 0, 500).collectList().block())
				.isEqualTo(RunState.PR_OPEN);

		try (Repository remote = new FileRepositoryBuilder().setGitDir(REMOTE.toFile()).build()) {
			var branch = remote.resolve("refs/heads/agent/" + id);
			assertThat(branch).isNotNull();
			assertThat(remote.parseCommit(branch).getFullMessage()).startsWith("Greet agents")
					.contains("Agentic-SDLC-Run: " + id);
		}
		FakeScmServer.Request post = GITHUB.requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow();
		assertThat(post.header("Authorization")).isEqualTo("Bearer test-token");
		assertThat(post.body()).contains("\"head\":\"agent/" + id + "\"", "\"base\":\"main\"", "Automated review");
		assertThat(queries.events(id, 0, 500).collectList().block())
				.filteredOn(e -> e.type() == RunEventType.ARTIFACT_PRODUCED && "pull-request".equals(e.payload().get("kind")))
				.singleElement().satisfies(e -> assertThat(e.payload()).containsEntry("url",
						"https://github.com/acme/publish-demo/pull/5"));

		assertThat(tracker.sweep().collectList().block()).doesNotContain(id);
		PR_STATE.set("merged");
		assertThat(tracker.sweep().collectList().block()).contains(id);
		assertThat(queries.get(id).block().run().state()).isEqualTo(RunState.DONE);
	}
}
