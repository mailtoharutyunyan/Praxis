package io.agenticsdlc.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.TestcontainersConfigurationAccess;
import io.agenticsdlc.adapter.out.scm.ScmHttp;
import io.agenticsdlc.adapter.out.scm.ScmPullRequests;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.application.NewTask;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.application.RunQueries;
import io.agenticsdlc.core.application.TaskIntake;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.engine.StageHandler;
import io.agenticsdlc.core.engine.StageOutcome;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.scm.PublishStage;
import io.agenticsdlc.core.scm.PullRequestFeedback;
import io.agenticsdlc.core.scm.PullRequests;
import io.agenticsdlc.support.FakeScmServer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Bitbucket Cloud and Azure DevOps webhooks end to end: a signed (or authenticated) event about a run's open pull
 * request, checked against the host's API (a fake), sends the run back for a revision. Stages are stubs except
 * publishing, which records a pull request as the real stage does.
 */
@Import({ TestcontainersConfigurationAccess.class, ScmWebhookTest.Publishing.class })
@SpringBootTest(properties = { "agentic.stub-stages.enabled=true", "agentic.sandbox.enabled=false",
		"agentic.worker.poll-interval=50ms", "agentic.scm.tokens[bitbucket.org]=bb-token",
		"agentic.scm.tokens[dev.azure.com]=ado-token", "agentic.scm.feedback.bitbucket-secret=bb-hook-secret",
		"agentic.scm.feedback.azure-devops-secret=ado-hook-secret" })
class ScmWebhookTest {

	private static final String COMMIT = "9fceb02d0ae598e95dc970b74767f19372d61af8";
	private static final FakeScmServer HOST;

	static {
		try {
			HOST = new FakeScmServer()
					// Bitbucket Cloud
					.on("GET", "/2.0/workspaces/acme/permissions/repositories/shop", r -> new FakeScmServer.Response(200,
							r.uri().contains("%7Bbob%7D")
									? "{\"values\":[{\"type\":\"repository_permission\",\"permission\":\"write\","
											+ "\"user\":{\"uuid\":\"{bob}\"}}]}"
									: "{\"values\":[{\"type\":\"repository_permission\",\"permission\":\"read\","
											+ "\"user\":{\"uuid\":\"{eve}\"}}]}"))
					.on("POST", "/2.0/repositories/acme/shop/pullrequests/7/comments", r -> new FakeScmServer.Response(201,
							"{}"))
					.on("GET", "/2.0/repositories/acme/shop/commit/" + COMMIT + "/statuses/build/3343801", r ->
							new FakeScmServer.Response(200, "{\"key\":\"3343801\",\"state\":\"FAILED\",\"name\":\"Pipeline #12\","
									+ "\"url\":\"https://bitbucket.org/acme/shop/addon/pipelines/home#!/results/12\"}"))
					.on("GET", "/2.0/repositories/acme/shop/pipelines", r -> new FakeScmServer.Response(200,
							"{\"values\":[{\"uuid\":\"{pl-12}\",\"build_number\":12}]}"))
					.on("GET", "/2.0/repositories/acme/shop/pipelines/%7Bpl-12%7D/steps", r -> new FakeScmServer.Response(200,
							"{\"values\":[{\"uuid\":\"{st-1}\",\"name\":\"Test\",\"state\":{\"name\":\"COMPLETED\","
									+ "\"result\":{\"name\":\"FAILED\"}}}]}"))
					.on("GET", "/2.0/repositories/acme/shop/pipelines/%7Bpl-12%7D/steps/%7Bst-1%7D/log", r ->
							new FakeScmServer.Response(200, "+ mvn verify\n[ERROR] GreetingTest expected 'hello agents'"))
					// Azure DevOps (organization "acme", project "Shop")
					.on("GET", "/acme/Shop/_apis/git/repositories/shop", r -> new FakeScmServer.Response(200,
							"{\"id\":\"c2c2c2c2-dddd-eeee-ffff-a3a3a3a3a3a3\",\"webUrl\":\"https://dev.azure.com/acme/Shop/_git/shop\","
									+ "\"project\":{\"id\":\"d3d3d3d3-eeee-ffff-aaaa-b4b4b4b4b4b4\"}}"))
					.on("POST", "/acme/Shop/_apis/git/repositories/c2c2c2c2-dddd-eeee-ffff-a3a3a3a3a3a3/pullRequests/7/threads",
							r -> new FakeScmServer.Response(200, "{}"))
					.on("GET", "/acme/_apis/identities", r -> new FakeScmServer.Response(200, "{\"value\":[{\"id\":\""
							+ (r.uri().contains("11bb11bb") ? "11bb11bb-cc22-dd33-ee44-55ff55ff55ff\",\"descriptor\":"
									+ "\"Microsoft.IdentityModel.Claims.ClaimsIdentity;acme\\\\bob@acme.com\"}]}"
									: "22cc22cc-dd33-ee44-ff55-66aa66aa66aa\",\"descriptor\":"
											+ "\"Microsoft.IdentityModel.Claims.ClaimsIdentity;acme\\\\eve@acme.com\"}]}")))
					.on("GET", "/acme/_apis/accesscontrollists/", r -> new FakeScmServer.Response(200,
							"{\"count\":1,\"value\":[{\"inheritPermissions\":true,\"token\":\"repoV2/d3d3/c2c2\",\"acesDictionary\":"
									+ "{\"d\":{\"allow\":0,\"deny\":0,\"extendedInfo\":{\"effectiveAllow\":"
									+ (r.uri().contains("bob%40acme.com") ? 16502 : 2) + ",\"effectiveDeny\":0}}}}]}"))
					.on("GET", "/acme/Shop/_apis/build/builds/2727068/timeline", r -> new FakeScmServer.Response(200,
							"{\"records\":[{\"id\":\"j1\",\"type\":\"Job\",\"name\":\"Build\",\"result\":\"failed\",\"log\":{\"id\":3}},"
									+ "{\"id\":\"t1\",\"parentId\":\"j1\",\"type\":\"Task\",\"name\":\"Maven\",\"result\":\"failed\","
									+ "\"log\":{\"id\":7}}]}"))
					.on("GET", "/acme/Shop/_apis/build/builds/2727068/logs/7", r -> new FakeScmServer.Response(200,
							"##[section]Starting: Maven\n[ERROR] GreetingTest expected 'hello agents'"));
		}
		catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@AfterAll
	static void stopServer() {
		HOST.close();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("agentic.scm.api-urls[bitbucket.org]", () -> HOST.url() + "/2.0");
		registry.add("agentic.scm.api-urls[dev.azure.com]", () -> HOST.url() + "/acme");
	}

	/**
	 * Publishing records pull request 7 for the last pushed commit, as {@link PublishStage} does; pull request feedback
	 * is wired as in production, which needs the sandbox there.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class Publishing {

		@Bean
		StageHandler recordingPublish() {
			return new StageHandler() {
				@Override
				public RunState stage() {
					return RunState.PUBLISHING;
				}

				@Override
				public Mono<StageOutcome> execute(StageContext context) {
					RepositoryRef repository = context.task().repository();
					String url = repository.kind() == ScmKind.BITBUCKET ? "https://bitbucket.org/acme/shop/pull-requests/7"
							: "https://dev.azure.com/acme/Shop/_git/shop/pullrequest/7";
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("kind", PublishStage.PULL_REQUEST);
					payload.put("id", "7");
					payload.put("url", url);
					payload.put("branch", "agent/" + context.run().id());
					payload.put("commit", COMMIT);
					payload.put("repository", repository.cloneUrl().toString());
					payload.put("scmKind", repository.kind().name());
					payload.put("content", url);
					return context.emit(RunEventType.ARTIFACT_PRODUCED, "system", payload)
							.thenReturn(StageOutcome.Completed.free());
				}
			};
		}

		@Bean
		ScmPullRequests webhookTestPullRequests(WebClient.Builder webClient, ConnectorSettings connectors) {
			return new ScmPullRequests(new ScmHttp(webClient, connectors::scmToken, connectors::scmApiUrl,
					Duration.ofSeconds(10)), false);
		}

		@Bean
		PullRequestFeedback webhookTestFeedback(RunStore store, RunCommands commands, PullRequests pullRequests,
				ConnectorSettings connectors) {
			return new PullRequestFeedback(store, commands, pullRequests, () -> connectors.feedback().mention(), 3,
					() -> "");
		}
	}

	@Autowired
	ApplicationContext context;

	@Autowired
	TaskIntake intake;

	@Autowired
	RunQueries queries;

	@Autowired
	RunCommands commands;

	private WebTestClient client() {
		return WebTestClient.bindToApplicationContext(context).configureClient().responseTimeout(Duration.ofSeconds(20))
				.build();
	}

	/** Submits a task and approves its gates until its pull request is open. */
	private UUID openPullRequest(ScmKind kind, String cloneUrl) throws InterruptedException {
		UUID id = intake.submit(new NewTask(TaskOrigin.PROMPT, null, "Greet agents", "hello agent",
				new RepositoryRef(kind, URI.create(cloneUrl)), null, "alice", null, null)).block().view().run().id();
		awaitPullRequestOpen(id);
		return id;
	}

	private void awaitPullRequestOpen(UUID id) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		Run run = queries.get(id).block().run();
		while (run.state() != RunState.PR_OPEN) {
			if (System.currentTimeMillis() > deadline || run.state().isTerminal() || run.state() == RunState.NEEDS_HUMAN) {
				throw new AssertionError("run is " + run.state() + "; events: " + queries.events(id, 0, 500).collectList()
						.block());
			}
			if (run.pendingGate() != null) {
				commands.decide(id, run.pendingGate(), GateDecision.APPROVE, "ok", "bob").block();
			}
			Thread.sleep(50);
			run = queries.get(id).block().run();
		}
	}

	private RunEvent lastRevision(UUID id) {
		return queries.events(id, 0, 1000).collectList().block().stream()
				.filter(e -> e.type() == RunEventType.REVISION_REQUESTED).reduce((first, second) -> second).orElseThrow();
	}

	private Map<String, Object> post(WebTestClient.RequestBodySpec request, String body, int status) {
		return request.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
				.expectStatus().isEqualTo(status)
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
				}).returnResult().getResponseBody();
	}

	private Map<String, Object> bitbucket(String event, String body, String secret, int status) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		return post(client().post().uri("/api/v1/webhooks/bitbucket").header("X-Event-Key", event)
				.header("X-Hub-Signature", signature).header("X-Request-UUID", UUID.randomUUID().toString()), body, status);
	}

	private Map<String, Object> azureDevOps(String body, String password, int status) {
		String basic = Base64.getEncoder().encodeToString(("agentic:" + password).getBytes(StandardCharsets.UTF_8));
		return post(client().post().uri("/api/v1/webhooks/azure-devops").header("Authorization", "Basic " + basic), body,
				status);
	}

	private static final String BITBUCKET_REPOSITORY = """
			{"type":"repository","full_name":"acme/shop","name":"shop","uuid":"{6f3c5a7e-1b2d-4c3e-9f8a-0b1c2d3e4f5a}",
			 "links":{"html":{"href":"https://bitbucket.org/acme/shop"}}}""";

	/** {@code pullrequest:comment_created}, as in Atlassian's event payload reference. */
	private static String bitbucketComment(long id, String uuid, String name, String text, UUID runId) {
		return """
				{"actor":{"type":"user","uuid":"%2$s","account_id":"557058:%3$s","display_name":"%3$s","nickname":"%3$s"},
				 "repository":%4$s,
				 "pullrequest":{"id":7,"title":"Greet agents","state":"OPEN",
				  "source":{"branch":{"name":"agent/%5$s"},"commit":{"hash":"9fceb02d0ae5"}},
				  "destination":{"branch":{"name":"main"}},
				  "links":{"html":{"href":"https://bitbucket.org/acme/shop/pull-requests/7"}}},
				 "comment":{"id":%1$d,"content":{"raw":"%6$s","markup":"markdown","html":"<p>%6$s</p>"},
				  "inline":{"path":"src/main/java/Greeting.java","from":null,"to":12},
				  "created_on":"2026-10-09T10:15:00.000000+00:00",
				  "links":{"html":{"href":"https://bitbucket.org/acme/shop/pull-requests/7/_/diff#comment-%1$d"}}}}"""
				.formatted(id, uuid, name, BITBUCKET_REPOSITORY, runId, text);
	}

	/** {@code repo:commit_status_updated} from Bitbucket Pipelines. */
	private static String bitbucketStatus(String state, UUID runId) {
		return """
				{"actor":{"type":"user","uuid":"{bob}","display_name":"Bob"},
				 "repository":%s,
				 "commit_status":{"key":"3343801","type":"build","state":"%s","name":"Pipeline #12 for agent/%s",
				  "description":"Tests failed","refname":"agent/%s",
				  "url":"https://bitbucket.org/acme/shop/addon/pipelines/home#!/results/12",
				  "commit":{"type":"commit","hash":"%s"},
				  "links":{"commit":{"href":"https://api.bitbucket.org/2.0/repositories/acme/shop/commit/%s"}},
				  "created_on":"2026-10-09T10:20:00.000000+00:00","updated_on":"2026-10-09T10:24:00.000000+00:00"}}"""
				.formatted(BITBUCKET_REPOSITORY, state, runId, runId, COMMIT, COMMIT);
	}

	/** {@code ms.vss-code.git-pullrequest-comment-event}, as in Microsoft's service hook event reference. */
	private static String azureComment(int thread, String authorId, String author, String text) {
		return """
				{"id":"a0a0a0a0-bbbb-cccc-dddd-e1e1e1e1e1e1","eventType":"ms.vss-code.git-pullrequest-comment-event",
				 "publisherId":"tfs","message":{"text":"%3$s has commented on a pull request"},
				 "resource":{
				  "comment":{"id":1,"parentCommentId":0,"author":{"displayName":"%3$s","id":"%2$s",
				    "uniqueName":"%3$s@acme.com"},
				   "content":"%4$s","commentType":"text","publishedDate":"2026-10-09T10:15:00Z",
				   "_links":{"self":{"href":"https://dev.azure.com/acme/_apis/git/repositories/c2c2c2c2-dddd-eeee-ffff-a3a3a3a3a3a3/pullRequests/7/threads/%1$d/comments/1"},
				    "threads":{"href":"https://dev.azure.com/acme/_apis/git/repositories/c2c2c2c2-dddd-eeee-ffff-a3a3a3a3a3a3/pullRequests/7/threads/%1$d"}}},
				  "pullRequest":{"repository":{"id":"c2c2c2c2-dddd-eeee-ffff-a3a3a3a3a3a3","name":"shop",
				    "url":"https://dev.azure.com/acme/_apis/git/repositories/c2c2c2c2-dddd-eeee-ffff-a3a3a3a3a3a3",
				    "project":{"id":"d3d3d3d3-eeee-ffff-aaaa-b4b4b4b4b4b4","name":"Shop"},
				    "remoteUrl":"https://acme@dev.azure.com/acme/Shop/_git/shop"},
				   "pullRequestId":7,"status":"active","title":"Greet agents",
				   "sourceRefName":"refs/heads/agent/r","targetRefName":"refs/heads/main",
				   "_links":{"web":{"href":"https://dev.azure.com/acme/Shop/_git/shop/pullrequest/7#view=discussion"}}}},
				 "resourceVersion":"2.0","createdDate":"2026-10-09T10:15:01Z"}"""
				.formatted(thread, authorId, author, text);
	}

	/** {@code build.complete}, as in Microsoft's service hook event reference. */
	private static String azureBuild(String result, UUID runId) {
		return """
				{"id":"b0b0b0b0-bbbb-cccc-dddd-e1e1e1e1e1e1","eventType":"build.complete","publisherId":"tfs",
				 "message":{"text":"Build 20261009.3 %1$s"},
				 "resource":{"_links":{"web":{"href":"https://dev.azure.com/acme/d3d3d3d3-eeee-ffff-aaaa-b4b4b4b4b4b4/_build/results?buildId=2727068"}},
				  "id":2727068,"buildNumber":"20261009.3","status":"completed","result":"%1$s",
				  "url":"https://dev.azure.com/acme/d3d3d3d3-eeee-ffff-aaaa-b4b4b4b4b4b4/_apis/build/Builds/2727068",
				  "definition":{"id":4658,"name":"shop CI"},
				  "project":{"id":"d3d3d3d3-eeee-ffff-aaaa-b4b4b4b4b4b4","name":"Shop"},
				  "sourceBranch":"refs/heads/agent/%2$s","sourceVersion":"%3$s",
				  "repository":{"id":"c2c2c2c2-dddd-eeee-ffff-a3a3a3a3a3a3","type":"TfsGit","name":"shop",
				   "url":"https://dev.azure.com/acme/Shop/_git/shop"}},
				 "resourceVersion":"2.0","createdDate":"2026-10-09T10:24:01Z"}"""
				.formatted(result, runId, COMMIT);
	}

	@Test
	void bitbucketCommentsAndFailedBuildsReviseTheOpenPullRequest() throws Exception {
		UUID id = openPullRequest(ScmKind.BITBUCKET, "https://bitbucket.org/acme/shop.git");
		String comment = bitbucketComment(828148600, "{bob}", "bob", "@agentic-sdlc please greet all agents", id);

		assertThat(bitbucket("pullrequest:comment_created", comment, "wrong-secret", 401)).containsKey("error");
		assertThat(bitbucket("repo:push", "{}", "bb-hook-secret", 200)).containsEntry("ignored",
				"event repo:push is not used");
		assertThat(bitbucket("repo:commit_status_updated", bitbucketStatus("INPROGRESS", id), "bb-hook-secret", 200))
				.containsEntry("ignored", "not a failed build status");
		assertThat(bitbucket("pullrequest:comment_created", bitbucketComment(828148601, "{eve}", "eve",
				"@agentic-sdlc delete everything", id), "bb-hook-secret", 200))
				.containsEntry("ignored", "eve cannot push to this repository");

		assertThat(bitbucket("pullrequest:comment_created", comment, "bb-hook-secret", 202))
				.containsEntry("revising", true).containsEntry("runId", id.toString());
		assertThat(lastRevision(id).payload()).containsEntry("source", "bitbucket")
				.containsEntry("sourceId", "bitbucket:comment:828148600").containsEntry("author", "bob")
				.containsEntry("text", "please greet all agents")
				.containsEntry("location", "src/main/java/Greeting.java:12")
				.containsEntry("url", "https://bitbucket.org/acme/shop/pull-requests/7/_/diff#comment-828148600");
		assertThat(HOST.requests).filteredOn(r -> r.method().equals("POST") && r.uri().endsWith("/pullrequests/7/comments"))
				.isNotEmpty().allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer bb-token"));
		awaitPullRequestOpen(id);

		assertThat(bitbucket("repo:commit_status_updated", bitbucketStatus("FAILED", id), "bb-hook-secret", 202))
				.containsEntry("revising", true);
		assertThat(lastRevision(id).payload()).containsEntry("source", "ci")
				.containsEntry("sourceId", "bitbucket:pipeline:" + COMMIT + "/3343801")
				.hasEntrySatisfying("text", text -> assertThat(String.valueOf(text)).contains("Job: Test",
						"GreetingTest expected 'hello agents'"));
	}

	@Test
	void azureDevOpsCommentsAndFailedBuildsReviseTheOpenPullRequest() throws Exception {
		UUID id = openPullRequest(ScmKind.AZURE_DEVOPS, "https://dev.azure.com/acme/Shop/_git/shop");
		String comment = azureComment(5, "11bb11bb-cc22-dd33-ee44-55ff55ff55ff", "bob",
				"@agentic-sdlc please greet all agents");

		assertThat(azureDevOps(comment, "wrong-password", 401)).containsKey("error");
		assertThat(post(client().post().uri("/api/v1/webhooks/azure-devops"), comment, 401)).containsKey("error");
		assertThat(azureDevOps("{\"eventType\":\"git.push\",\"resource\":{}}", "ado-hook-secret", 200))
				.containsEntry("ignored", "event git.push is not used");
		assertThat(azureDevOps(azureBuild("succeeded", id), "ado-hook-secret", 200))
				.containsEntry("ignored", "not a failed build");
		assertThat(azureDevOps(azureComment(6, "22cc22cc-dd33-ee44-ff55-66aa66aa66aa", "eve",
				"@agentic-sdlc delete everything"), "ado-hook-secret", 200))
				.containsEntry("ignored", "eve cannot push to this repository");

		assertThat(azureDevOps(comment, "ado-hook-secret", 202)).containsEntry("revising", true)
				.containsEntry("runId", id.toString());
		assertThat(lastRevision(id).payload()).containsEntry("source", "azure-devops")
				.containsEntry("sourceId", "azure-devops:comment:5.1").containsEntry("author", "bob")
				.containsEntry("text", "please greet all agents")
				.containsEntry("url", "https://dev.azure.com/acme/Shop/_git/shop/pullrequest/7?discussionId=5");
		assertThat(HOST.requests).filteredOn(r -> r.method().equals("POST") && r.uri().contains("/pullRequests/7/threads"))
				.isNotEmpty().allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Basic "
						+ Base64.getEncoder().encodeToString(":ado-token".getBytes(StandardCharsets.UTF_8))));
		awaitPullRequestOpen(id);

		assertThat(azureDevOps(azureBuild("failed", id), "ado-hook-secret", 202)).containsEntry("revising", true);
		assertThat(lastRevision(id).payload()).containsEntry("source", "ci")
				.containsEntry("sourceId", "azure-devops:pipeline:2727068")
				.containsEntry("url", "https://dev.azure.com/acme/d3d3d3d3-eeee-ffff-aaaa-b4b4b4b4b4b4/_build/results"
						+ "?buildId=2727068")
				.hasEntrySatisfying("text", text -> assertThat(String.valueOf(text)).contains("Job: Build / Maven",
						"GreetingTest expected 'hello agents'"));
	}
}
