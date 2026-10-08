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
import io.agenticsdlc.core.domain.Companion;
import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
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
 * ADR-0006 end to end across repositories: an API and its web consumer change in one run. Both are verified, both
 * get a branch and a cross-linked pull request, and the run is DONE only when both are merged.
 */
@Import({ TestcontainersConfiguration.class, MultiRepoPipelineTest.ScriptedModels.class })
@SpringBootTest(properties = { "agentic.sandbox.enabled=true", "agentic.agent.enabled=true",
		"agentic.stub-stages.enabled=false", "agentic.sandbox.network=none", "agentic.sandbox.memory=256MB",
		"agentic.worker.poll-interval=50ms", "agentic.agent.tests-first=false", "agentic.agent.spec-critic=false",
		"agentic.memory.enabled=false", "agentic.scan.secrets=false", "agentic.scm.tokens[github.com]=test-token",
		"agentic.scm.pull-request-poll-interval=1h" })
class MultiRepoPipelineTest {

	private static final String API = "https://github.com/acme/orders-api.git";
	private static final String WEB = "https://github.com/acme/orders-web.git";
	private static final Path ROOT;
	private static final Path API_REMOTE;
	private static final Path WEB_REMOTE;
	private static final FakeScmServer GITHUB;
	private static final Map<String, String> STATES = new ConcurrentHashMap<>(Map.of("11", "open", "21", "open"));

	static {
		try {
			ROOT = Files.createTempDirectory("agentic-multirepo");
			API_REMOTE = bare(TestRepos.createRepo(ROOT.resolve("api"), Map.of(
					".agentic-sdlc.yml", "image: " + TestRepos.ALPINE + "\nbuild: test -f api.txt\ntest: grep -q v1 api.txt\n",
					"api.txt", "orders v1\n")), "api.git");
			WEB_REMOTE = bare(TestRepos.createRepo(ROOT.resolve("web"), Map.of(
					".agentic-sdlc.yml", "image: " + TestRepos.ALPINE + "\nbuild: test -f client.txt\ntest: grep -q orders client.txt\n",
					"client.txt", "calls orders v1\n")), "web.git");
			GITHUB = new FakeScmServer()
					.on("GET", "/repos/acme/orders-api/pulls/11", r -> pr("11"))
					.on("GET", "/repos/acme/orders-web/pulls/21", r -> pr("21"))
					.on("GET", "/repos/acme/orders-api/pulls", r -> new FakeScmServer.Response(200, "[]"))
					.on("GET", "/repos/acme/orders-web/pulls", r -> new FakeScmServer.Response(200, "[]"))
					.on("POST", "/repos/acme/orders-api/pulls", r -> new FakeScmServer.Response(201,
							"{\"number\":11,\"html_url\":\"https://github.com/acme/orders-api/pull/11\"}"))
					.on("POST", "/repos/acme/orders-web/pulls", r -> new FakeScmServer.Response(201,
							"{\"number\":21,\"html_url\":\"https://github.com/acme/orders-web/pull/21\"}"))
					.on("POST", "/repos/acme/orders-api/issues/11/comments", r -> new FakeScmServer.Response(201, "{}"))
					.on("POST", "/repos/acme/orders-web/issues/21/comments", r -> new FakeScmServer.Response(201, "{}"));
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static Path bare(Path source, String name) throws Exception {
		Path remote = ROOT.resolve(name);
		try (Git git = Git.cloneRepository().setURI(source.toUri().toString()).setBare(true).setDirectory(remote.toFile())
				.call()) {
			return remote;
		}
	}

	private static FakeScmServer.Response pr(String number) {
		boolean merged = STATES.get(number).equals("merged");
		return new FakeScmServer.Response(200, "{\"number\":" + number + ",\"state\":\"" + (merged ? "closed" : "open")
				+ "\",\"merged\":" + merged + "}");
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.sandbox.workspace-root", () -> ROOT.resolve("workspaces").toString());
		registry.add("agentic.scm.mirrors[" + API + "]", () -> API_REMOTE.toUri().toString());
		registry.add("agentic.scm.mirrors[" + WEB + "]", () -> WEB_REMOTE.toUri().toString());
		registry.add("agentic.scm.api-urls[github.com]", GITHUB::url);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ScriptedModels {
		@Bean
		@Primary
		AgentModels multiRepoScriptedModels() {
			Map<AgentRole, Deque<ModelReply>> scripts = new EnumMap<>(AgentRole.class);
			scripts.put(AgentRole.TRIAGE, new ArrayDeque<>(List.of(answer("RISK: LOW\nRATIONALE: versioned rename"))));
			scripts.put(AgentRole.PLANNER, new ArrayDeque<>(List.of(answer("## Requirements\n1. THE API SHALL be v2."))));
			scripts.put(AgentRole.CODER, new ArrayDeque<>(List.of(
					call("edit_file", Map.of("path", "api.txt", "old_string", "v1", "new_string", "v1 v2")),
					call("edit_file", Map.of("path", ".repos/orders-web/client.txt", "old_string", "orders v1",
							"new_string", "orders v2")),
					answer("Bumped the API and its client."))));
			scripts.put(AgentRole.REVIEWER, new ArrayDeque<>(List.of(answer("Both sides match.\nVERDICT: APPROVE"))));
			return role -> new AgentModel() {
				@Override
				public String id() {
					return "scripted/" + role;
				}

				@Override
				public Mono<ModelReply> complete(ModelRequest request) {
					ModelReply next = scripts.get(role).poll();
					return next == null ? Mono.error(new IllegalStateException("no more replies for " + role)) : Mono.just(next);
				}
			};
		}

		private static ModelReply call(String tool, Map<String, Object> args) {
			return new ModelReply("", List.of(new ToolCall(UUID.randomUUID().toString(), tool, args, args.toString())),
					Usage.ZERO, "tool_use");
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
		long deadline = System.currentTimeMillis() + 180_000;
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

	private static boolean hasPath(Path bareRepo, String branch, String path) throws Exception {
		try (Repository remote = new FileRepositoryBuilder().setGitDir(bareRepo.toFile()).build();
				org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(remote)) {
			var tree = walk.parseCommit(remote.resolve("refs/heads/" + branch)).getTree();
			try (TreeWalk found = TreeWalk.forPath(remote, path, tree)) {
				return found != null;
			}
		}
	}

	@Test
	void oneRunChangesAnApiAndItsConsumerWithLinkedPullRequests() throws Exception {
		RepositoryRef web = new RepositoryRef(ScmKind.GITHUB, URI.create(WEB));
		UUID id = intake.submit(new NewTask(TaskOrigin.PROMPT, null, "Orders API v2", "Bump to v2 and update the web client",
				new RepositoryRef(ScmKind.GITHUB, URI.create(API)), null, "alice", null,
				List.of(new Companion(Companion.aliasFor(web), web, null)))).block().view().run().id();

		Run atPublish = await(id, r -> r.pendingGate() == Gate.PUBLISH || r.state() == RunState.NEEDS_HUMAN
				|| r.state().isTerminal());
		List<RunEvent> events = queries.events(id, 0, 1000).collectList().block();
		assertThat(atPublish.pendingGate()).as("events: %s", events).isEqualTo(Gate.PUBLISH);
		assertThat(events).filteredOn(e -> e.type() == RunEventType.ARTIFACT_PRODUCED && "diff".equals(e.payload().get("kind")))
				.last().satisfies(e -> assertThat(String.valueOf(e.payload().get("content")))
						.contains("+++ b/api.txt", "+++ b/.repos/orders-web/client.txt"));
		long verifyStart = events.stream().filter(e -> e.type() == RunEventType.STATE_CHANGED
				&& "VERIFYING".equals(e.payload().get("to"))).findFirst().orElseThrow().seq();
		assertThat(events).filteredOn(e -> e.type() == RunEventType.COMMAND_OUTPUT && e.seq() > verifyStart)
				.extracting(e -> e.payload().get("service")).as("both repositories' services are verified")
				.contains("app", "orders-web");

		commands.decide(id, Gate.PUBLISH, GateDecision.APPROVE, "ship both", "bob").block();
		Run open = await(id, r -> r.state() == RunState.PR_OPEN || r.state() == RunState.NEEDS_HUMAN);
		assertThat(open.state()).as("events: %s", queries.events(id, 0, 1000).collectList().block())
				.isEqualTo(RunState.PR_OPEN);

		String branch = "agent/" + id;
		assertThat(hasPath(API_REMOTE, branch, "api.txt")).isTrue();
		assertThat(hasPath(API_REMOTE, branch, ".repos/orders-web/client.txt")).as("companions stay out of the primary")
				.isFalse();
		assertThat(hasPath(WEB_REMOTE, branch, "client.txt")).isTrue();
		assertThat(queries.events(id, 0, 1000).collectList().block())
				.filteredOn(e -> e.type() == RunEventType.ARTIFACT_PRODUCED && "pull-request".equals(e.payload().get("kind")))
				.extracting(e -> e.payload().get("url")).containsExactly("https://github.com/acme/orders-api/pull/11",
						"https://github.com/acme/orders-web/pull/21");
		assertThat(GITHUB.requests).filteredOn(r -> r.method().equals("POST") && r.uri().contains("/comments"))
				.hasSize(2).allSatisfy(r -> assertThat(r.body()).contains("orders-api/pull/11", "orders-web/pull/21"));
		assertThat(GITHUB.requests).filteredOn(r -> r.method().equals("POST") && r.uri().endsWith("/orders-web/pulls"))
				.singleElement().satisfies(r -> assertThat(r.body()).contains("Part of one change across 2 repositories"));

		STATES.put("11", "merged");
		assertThat(tracker.sweep().collectList().block()).as("one of two merged").doesNotContain(id);
		STATES.put("21", "merged");
		assertThat(tracker.sweep().collectList().block()).contains(id);
		assertThat(queries.get(id).block().run().state()).isEqualTo(RunState.DONE);
	}
}
