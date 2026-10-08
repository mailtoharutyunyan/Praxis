package io.agenticsdlc.adapter.out.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.scm.PullRequests;
import io.agenticsdlc.core.scm.PullRequests.PullRequestState;
import io.agenticsdlc.support.FakeScmServer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class ScmPullRequestsTest {

	private static final PullRequests.OpenRequest OPEN = new PullRequests.OpenRequest("agent/r1", "main", "SHOP-1: Add search",
			"body");

	private FakeScmServer server;

	@BeforeEach
	void start() throws Exception {
		server = new FakeScmServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private ScmPullRequests client(String host, String apiBase) {
		return new ScmPullRequests(new ScmHttp(WebClient.builder(), Map.of(host, "s3cr3t"), Map.of(host, apiBase),
				Duration.ofSeconds(5)), true);
	}

	private static RunView view(ScmKind kind, String url) {
		Task task = new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "t", "d", new RepositoryRef(kind, URI.create(url)),
				null, Trust.TRUSTED, "alice", null, Instant.now());
		return new RunView(Run.start(UUID.randomUUID(), task.id(), Instant.now()), task);
	}

	@Test
	void gitHubCreatesOnceThenFindsTheExistingPullRequest() {
		AtomicBoolean created = new AtomicBoolean();
		server.on("GET", "/repos/acme/shop/pulls/7", r -> new FakeScmServer.Response(200,
				"{\"number\":7,\"state\":\"closed\",\"merged\":true,\"merged_at\":\"2026-10-08T10:00:00Z\"}"))
				.on("GET", "/repos/acme/shop/pulls", r -> new FakeScmServer.Response(200, created.get()
						? "[{\"number\":7,\"html_url\":\"https://github.com/acme/shop/pull/7\"}]" : "[]"))
				.on("POST", "/repos/acme/shop/pulls", r -> {
					created.set(true);
					return new FakeScmServer.Response(201, "{\"number\":7,\"html_url\":\"https://github.com/acme/shop/pull/7\"}");
				});
		ScmPullRequests prs = client("github.com", server.url());
		RunView view = view(ScmKind.GITHUB, "https://github.com/acme/shop.git");

		PullRequests.PullRequest first = prs.open(view, OPEN).block();
		PullRequests.PullRequest second = prs.open(view, OPEN).block();

		assertThat(first).isEqualTo(new PullRequests.PullRequest("7", "https://github.com/acme/shop/pull/7"));
		assertThat(second).isEqualTo(first);
		assertThat(server.requests.stream().filter(r -> r.method().equals("POST")).count()).isEqualTo(1);
		FakeScmServer.Request post = server.requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow();
		assertThat(post.header("Authorization")).isEqualTo("Bearer s3cr3t");
		assertThat(post.header("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
		assertThat(post.body()).contains("\"head\":\"agent/r1\"", "\"base\":\"main\"", "\"draft\":true");
		assertThat(server.requests.getFirst().uri()).contains("head=acme%3Aagent%2Fr1", "state=all");
		assertThat(prs.state(view, first).block()).isEqualTo(PullRequestState.MERGED);
	}

	@Test
	void aCreateThatFailedAfterTakingEffectIsFoundNotRepeated() {
		AtomicBoolean created = new AtomicBoolean();
		server.on("GET", "/repos/acme/shop/pulls", r -> new FakeScmServer.Response(200, created.get()
				? "[{\"number\":8,\"html_url\":\"https://github.com/acme/shop/pull/8\"}]" : "[]"))
				.on("POST", "/repos/acme/shop/pulls", r -> {
					created.set(true);
					return new FakeScmServer.Response(502, "{\"message\":\"Bad gateway\"}");
				});
		ScmPullRequests prs = new ScmPullRequests(new ScmHttp(WebClient.builder(), Map.of("github.com", "s3cr3t"),
				Map.of("github.com", server.url()), Duration.ofSeconds(5)), false, Duration.ofMillis(10));

		PullRequests.PullRequest pr = prs.open(view(ScmKind.GITHUB, "https://github.com/acme/shop.git"), OPEN).block();

		assertThat(pr.id()).isEqualTo("8");
		assertThat(server.requests.stream().filter(r -> r.method().equals("POST")).count()).isEqualTo(1);
	}

	@Test
	void gitLabLockedMergeRequestsAreStillOpen() {
		server.on("GET", "/api/v4/projects/acme%2Fshop/merge_requests/4", r -> new FakeScmServer.Response(200,
				"{\"iid\":4,\"state\":\"locked\"}"));
		ScmPullRequests prs = client("gitlab.com", server.url() + "/api/v4");
		assertThat(prs.state(view(ScmKind.GITLAB, "https://gitlab.com/acme/shop.git"),
				new PullRequests.PullRequest("4", "u")).block()).isEqualTo(PullRequestState.OPEN);
	}

	@Test
	void gitLabUsesEncodedProjectPathAndDraftTitle() {
		server.on("GET", "/api/v4/projects/group%2Fsub%2Fshop/merge_requests/3", r -> new FakeScmServer.Response(200,
				"{\"iid\":3,\"state\":\"closed\"}"))
				.on("GET", "/api/v4/projects/group%2Fsub%2Fshop/merge_requests", r -> new FakeScmServer.Response(200, "[]"))
				.on("POST", "/api/v4/projects/group%2Fsub%2Fshop/merge_requests", r -> new FakeScmServer.Response(201,
						"{\"iid\":3,\"web_url\":\"https://gitlab.com/group/sub/shop/-/merge_requests/3\"}"));
		ScmPullRequests prs = client("gitlab.com", server.url() + "/api/v4");
		RunView view = view(ScmKind.GITLAB, "https://gitlab.com/group/sub/shop.git");

		PullRequests.PullRequest mr = prs.open(view, OPEN).block();
		assertThat(mr.id()).isEqualTo("3");
		FakeScmServer.Request post = server.requests.getLast();
		assertThat(post.header("PRIVATE-TOKEN")).isEqualTo("s3cr3t");
		assertThat(post.body()).contains("\"title\":\"Draft: SHOP-1: Add search\"", "\"source_branch\":\"agent/r1\"");
		assertThat(prs.state(view, mr).block()).isEqualTo(PullRequestState.CLOSED);
	}

	@Test
	void bitbucketQueriesAllStatesAndMapsDeclined() {
		server.on("GET", "/2.0/repositories/ws/shop/pullrequests/9", r -> new FakeScmServer.Response(200,
				"{\"id\":9,\"state\":\"DECLINED\"}"))
				.on("GET", "/2.0/repositories/ws/shop/pullrequests", r -> new FakeScmServer.Response(200,
						"{\"values\":[{\"id\":9,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/ws/shop/pull-requests/9\"}}}]}"));
		ScmPullRequests prs = client("bitbucket.org", server.url() + "/2.0");
		RunView view = view(ScmKind.BITBUCKET, "https://bitbucket.org/ws/shop.git");

		PullRequests.PullRequest pr = prs.open(view, OPEN).block();
		assertThat(pr.url()).isEqualTo("https://bitbucket.org/ws/shop/pull-requests/9");
		assertThat(server.requests.getFirst().uri()).contains("state=MERGED", "state=DECLINED", "source.branch.name");
		assertThat(server.requests.stream().noneMatch(r -> r.method().equals("POST"))).isTrue();
		assertThat(prs.state(view, pr).block()).isEqualTo(PullRequestState.CLOSED);
	}

	@Test
	void azureDevOpsResolvesRepositoryAndBuildsWebUrl() {
		server.on("GET", "/org/Proj/_apis/git/repositories/repo", r -> new FakeScmServer.Response(200,
				"{\"id\":\"guid-1\",\"webUrl\":\"https://dev.azure.com/org/Proj/_git/repo\"}"))
				.on("GET", "/org/Proj/_apis/git/repositories/guid-1/pullrequests/11", r -> new FakeScmServer.Response(200,
						"{\"pullRequestId\":11,\"status\":\"completed\"}"))
				.on("GET", "/org/Proj/_apis/git/repositories/guid-1/pullrequests", r -> new FakeScmServer.Response(200,
						"{\"value\":[]}"))
				.on("POST", "/org/Proj/_apis/git/repositories/guid-1/pullrequests", r -> new FakeScmServer.Response(201,
						"{\"pullRequestId\":11}"));
		ScmPullRequests prs = client("dev.azure.com", server.url() + "/org");
		RunView view = view(ScmKind.AZURE_DEVOPS, "https://dev.azure.com/org/Proj/_git/repo");

		PullRequests.PullRequest pr = prs.open(view, OPEN).block();
		assertThat(pr).isEqualTo(new PullRequests.PullRequest("11", "https://dev.azure.com/org/Proj/_git/repo/pullrequest/11"));
		FakeScmServer.Request post = server.requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow();
		assertThat(post.uri()).endsWith("api-version=7.1");
		assertThat(post.header("Authorization")).isEqualTo("Basic "
				+ java.util.Base64.getEncoder().encodeToString(":s3cr3t".getBytes()));
		assertThat(post.body()).contains("\"sourceRefName\":\"refs/heads/agent/r1\"", "\"isDraft\":true");
		assertThat(prs.state(view, pr).block()).isEqualTo(PullRequestState.MERGED);
	}

	@Test
	void bitbucketCanWriteReadsTheEffectivePermissionOfThatUser() {
		server.on("GET", "/2.0/workspaces/ws/permissions/repositories/shop", r -> new FakeScmServer.Response(200,
				r.uri().contains("%7Bbob%7D")
						? "{\"values\":[{\"permission\":\"write\",\"user\":{\"uuid\":\"{bob}\"}}]}"
						: "{\"values\":[{\"permission\":\"read\",\"user\":{\"uuid\":\"{eve}\"}}]}"));
		ScmPullRequests prs = client("bitbucket.org", server.url() + "/2.0");
		RunView view = view(ScmKind.BITBUCKET, "https://bitbucket.org/ws/shop.git");

		assertThat(prs.canWrite(view, "{bob}").block()).isTrue();
		assertThat(prs.canWrite(view, "{eve}").block()).isFalse();
		assertThat(server.requests.getFirst().uri()).contains("q=user.uuid%3D%22%7Bbob%7D%22");
		assertThat(server.requests.getFirst().header("Authorization")).isEqualTo("Bearer s3cr3t");
	}

	@Test
	void bitbucketPipelinesBuildYieldsFailedStepsWithTheirLogs() {
		server.on("GET", "/2.0/repositories/ws/shop/commit/abc123/statuses/build/77", r -> new FakeScmServer.Response(200,
				"{\"key\":\"77\",\"state\":\"FAILED\",\"name\":\"Pipeline #12 for agent/r1\","
						+ "\"url\":\"https://bitbucket.org/ws/shop/addon/pipelines/home#!/results/12\"}"))
				.on("GET", "/2.0/repositories/ws/shop/pipelines", r -> new FakeScmServer.Response(200,
						"{\"values\":[{\"uuid\":\"{p-11}\",\"build_number\":11},{\"uuid\":\"{p-12}\",\"build_number\":12}]}"))
				.on("GET", "/2.0/repositories/ws/shop/pipelines/%7Bp-12%7D/steps", r -> new FakeScmServer.Response(200,
						"{\"values\":[{\"uuid\":\"{s-1}\",\"name\":\"Lint\",\"state\":{\"name\":\"COMPLETED\","
								+ "\"result\":{\"name\":\"SUCCESSFUL\"}}},{\"uuid\":\"{s-2}\",\"name\":\"Test\","
								+ "\"state\":{\"name\":\"COMPLETED\",\"result\":{\"name\":\"FAILED\"}}}]}"))
				.on("GET", "/2.0/repositories/ws/shop/pipelines/%7Bp-12%7D/steps/%7Bs-2%7D/log", r ->
						new FakeScmServer.Response(307, "", "/storage/step-2.log"))
				.on("GET", "/storage/step-2.log", r -> new FakeScmServer.Response(200, "+ mvn verify\n[ERROR] FooTest"));
		ScmPullRequests prs = client("bitbucket.org", server.url() + "/2.0");

		assertThat(prs.failedJobs(view(ScmKind.BITBUCKET, "https://bitbucket.org/ws/shop.git"), "abc123/77").block())
				.containsExactly(new PullRequests.FailedJob("Test", "", "+ mvn verify\n[ERROR] FooTest"));
		assertThat(server.requests).filteredOn(r -> r.uri().contains("/pipelines?")).singleElement()
				.satisfies(r -> assertThat(r.uri()).contains("target.commit.hash=abc123"));
		assertThat(server.requests).filteredOn(r -> r.uri().startsWith("/storage/")).singleElement()
				.satisfies(r -> assertThat(r.header("Authorization")).isNull());
	}

	@Test
	void bitbucketBuildFromAnotherCiServerYieldsItsStatus() {
		server.on("GET", "/2.0/repositories/ws/shop/commit/abc123/statuses/build/jenkins%2Fci", r ->
				new FakeScmServer.Response(200, "{\"key\":\"jenkins/ci\",\"state\":\"FAILED\",\"name\":\"Jenkins\","
						+ "\"description\":\"3 tests failed\",\"url\":\"https://ci.example.com/job/7\"}"));
		ScmPullRequests prs = client("bitbucket.org", server.url() + "/2.0");
		RunView view = view(ScmKind.BITBUCKET, "https://bitbucket.org/ws/shop.git");

		assertThat(prs.failedJobs(view, "abc123/jenkins/ci").block()).containsExactly(
				new PullRequests.FailedJob("Jenkins", "https://ci.example.com/job/7", "3 tests failed"));
		assertThat(prs.failedJobs(view, "no-key").block()).isEmpty();
	}

	@Test
	void azureDevOpsCanWriteEvaluatesTheRepositoryAclForTheIdentity() {
		server.on("GET", "/org/Proj/_apis/git/repositories/repo", r -> new FakeScmServer.Response(200,
				"{\"id\":\"repo-guid\",\"webUrl\":\"https://dev.azure.com/org/Proj/_git/repo\",\"project\":{\"id\":\"proj-guid\"}}"))
				.on("GET", "/org/_apis/identities", r -> new FakeScmServer.Response(200, r.uri().contains("bob-id")
						? "{\"value\":[{\"id\":\"bob-id\",\"descriptor\":\"Microsoft.IdentityModel.Claims.ClaimsIdentity;t\\\\bob\"}]}"
						: r.uri().contains("eve-id")
								? "{\"value\":[{\"id\":\"eve-id\",\"descriptor\":\"Microsoft.IdentityModel.Claims.ClaimsIdentity;t\\\\eve\"}]}"
								: "{\"value\":[]}"))
				.on("GET", "/org/_apis/accesscontrollists/" + AzureDevOpsProvider.GIT_REPOSITORIES_NAMESPACE, r ->
						new FakeScmServer.Response(200, r.uri().contains("bob")
								? "{\"value\":[{\"acesDictionary\":{\"d\":{\"extendedInfo\":{\"effectiveAllow\":16502,\"effectiveDeny\":0}}}}]}"
								: "{\"value\":[{\"acesDictionary\":{\"d\":{\"extendedInfo\":{\"effectiveAllow\":6,\"effectiveDeny\":4}}}}]}"));
		ScmPullRequests prs = client("dev.azure.com", server.url() + "/org");
		RunView view = view(ScmKind.AZURE_DEVOPS, "https://dev.azure.com/org/Proj/_git/repo");

		assertThat(prs.canWrite(view, "bob-id").block()).isTrue();
		assertThat(prs.canWrite(view, "eve-id").block()).as("an explicit deny wins").isFalse();
		assertThat(prs.canWrite(view, "nobody").block()).isFalse();
		assertThat(server.requests).filteredOn(r -> r.uri().contains("/accesscontrollists/")).first()
				.satisfies(r -> assertThat(r.uri()).contains("token=repoV2%2Fproj-guid%2Frepo-guid", "includeExtendedInfo=true"));
	}

	@Test
	void azureDevOpsBuildYieldsFailedTasksFromItsTimeline() {
		server.on("GET", "/org/Proj/_apis/build/builds/42/timeline", r -> new FakeScmServer.Response(200, """
				{"records":[
				 {"id":"job","type":"Job","name":"Build","result":"failed","log":{"id":3}},
				 {"id":"t1","parentId":"job","type":"Task","name":"Checkout","result":"succeeded","log":{"id":4}},
				 {"id":"t2","parentId":"job","type":"Task","name":"Maven verify","result":"failed","log":{"id":5}}]}"""))
				.on("GET", "/org/Proj/_apis/build/builds/42/logs/5", r -> new FakeScmServer.Response(200,
						"##[section]Starting: Maven verify\n[ERROR] FooTest"));
		ScmPullRequests prs = client("dev.azure.com", server.url() + "/org");

		assertThat(prs.failedJobs(view(ScmKind.AZURE_DEVOPS, "https://dev.azure.com/org/Proj/_git/repo"), "42").block())
				.containsExactly(new PullRequests.FailedJob("Build / Maven verify", "",
						"##[section]Starting: Maven verify\n[ERROR] FooTest"));
		assertThat(server.requests.getLast().header("Accept")).isEqualTo("text/plain");
	}

	@Test
	void errorsCarryStatusButNeverTheToken() {
		server.on("GET", "/repos/acme/shop/pulls", r -> new FakeScmServer.Response(403, "{\"message\":\"Resource not accessible\"}"));
		ScmPullRequests prs = client("github.com", server.url());
		assertThatThrownBy(() -> prs.open(view(ScmKind.GITHUB, "https://github.com/acme/shop.git"), OPEN).block())
				.isInstanceOf(ScmHttp.ScmException.class)
				.hasMessageContaining("403").hasMessageContaining("Resource not accessible")
				.hasMessageNotContaining("s3cr3t");
	}

	@Test
	void missingTokenIsAClearError() {
		ScmPullRequests prs = new ScmPullRequests(new ScmHttp(WebClient.builder(), Map.of(), Map.of(), Duration.ofSeconds(5)),
				false);
		assertThatThrownBy(() -> prs.open(view(ScmKind.GITHUB, "https://github.com/acme/shop.git"), OPEN).block())
				.hasMessageContaining("no SCM token configured for github.com");
	}
}
