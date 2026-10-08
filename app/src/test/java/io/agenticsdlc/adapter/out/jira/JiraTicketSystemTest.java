package io.agenticsdlc.adapter.out.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.intake.Ticket;
import io.agenticsdlc.support.FakeScmServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

class JiraTicketSystemTest {

	static final String ISSUE = """
			{"key":"SHOP-7","fields":{"summary":"Add search","labels":["agentic","backend"],"project":{"key":"SHOP"},
			 "description":{"type":"doc","version":1,"content":[
			  {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Goal"}]},
			  {"type":"paragraph","content":[{"type":"text","text":"Users want "},{"type":"text","text":"search","marks":[{"type":"strong"}]},
			    {"type":"hardBreak"},{"type":"text","text":"ask "},{"type":"mention","attrs":{"text":"@Bob"}}]},
			  {"type":"bulletList","content":[
			    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"by name"}]}]},
			    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"by tag"}]}]}]},
			  {"type":"codeBlock","attrs":{"language":"sql"},"content":[{"type":"text","text":"select 1"}]},
			  {"type":"paragraph","content":[{"type":"inlineCard","attrs":{"url":"https://docs.example/x"}}]}]}}}""";

	private final FakeScmServer jira;

	JiraTicketSystemTest() throws Exception {
		jira = new FakeScmServer()
				.on("GET", "/rest/api/3/issue/SHOP-7", r -> new FakeScmServer.Response(200, ISSUE))
				.on("POST", "/rest/api/3/issue/SHOP-7/comment", r -> new FakeScmServer.Response(201, "{\"id\":\"1\"}"));
	}

	@AfterEach
	void stop() {
		jira.close();
	}

	@Test
	void readsIssueAndConvertsDescription() {
		JiraTicketSystem system = new JiraTicketSystem(WebClient.builder(), jira.url() + "/", "bot@acme.com", "tok",
				Duration.ofSeconds(5));
		Ticket ticket = system.fetch("SHOP-7").block();

		assertThat(ticket.projectKey()).isEqualTo("SHOP");
		assertThat(ticket.labels()).containsExactlyInAnyOrder("agentic", "backend");
		assertThat(ticket.url()).isEqualTo(jira.url() + "/browse/SHOP-7");
		assertThat(ticket.description()).isEqualTo("""
				## Goal

				Users want search
				ask @Bob

				- by name
				- by tag

				```sql
				select 1
				```

				https://docs.example/x""");
		assertThat(jira.requests.getFirst().header("Authorization")).isEqualTo("Basic "
				+ java.util.Base64.getEncoder().encodeToString("bot@acme.com:tok".getBytes()));
	}

	@Test
	void commentsAreAdfWithALinkAndBearerForPats() {
		JiraTicketSystem system = new JiraTicketSystem(WebClient.builder(), jira.url(), "", "pat", Duration.ofSeconds(5));
		system.comment("SHOP-7", "Run started.", "https://agentic.example.com/runs/1").block();

		var post = jira.requests.getLast();
		assertThat(post.header("Authorization")).isEqualTo("Bearer pat");
		var body = JsonMapper.builder().build().readTree(post.body());
		assertThat(body.path("body").path("type").asString()).isEqualTo("doc");
		var inline = body.path("body").path("content").get(0).path("content");
		assertThat(inline.get(0).path("text").asString()).isEqualTo("Run started.");
		assertThat(inline.get(2).path("marks").get(0).path("attrs").path("href").asString())
				.isEqualTo("https://agentic.example.com/runs/1");
	}

	@Test
	void dataCenterUsesRestV2WithPlainStrings() throws Exception {
		try (FakeScmServer dc = new FakeScmServer()
				.on("GET", "/rest/api/2/issue/SHOP-8", r -> new FakeScmServer.Response(200, """
						{"key":"SHOP-8","fields":{"summary":"S","labels":["agentic"],"project":{"key":"SHOP"},
						 "description":"h2. Goal\\nPlain *wiki* text"}}"""))
				.on("POST", "/rest/api/2/issue/SHOP-8/comment", r -> new FakeScmServer.Response(201, "{}"))) {
			JiraTicketSystem system = new JiraTicketSystem(WebClient.builder(), dc.url(), "", "pat", Duration.ofSeconds(5),
					false);
			assertThat(system.fetch("SHOP-8").block().description()).isEqualTo("h2. Goal\nPlain *wiki* text");
			system.comment("SHOP-8", "Run started.", "http://x/#/runs/1").block();
			var post = dc.requests.getLast();
			assertThat(post.header("Authorization")).isEqualTo("Bearer pat");
			assertThat(JsonMapper.builder().build().readTree(post.body()).path("body").asString())
					.isEqualTo("Run started.\nhttp://x/#/runs/1");
		}
	}

	@Test
	void rejectsKeysThatAreNotIssueKeys() {
		JiraTicketSystem system = new JiraTicketSystem(WebClient.builder(), jira.url(), "", "pat", Duration.ofSeconds(5));
		assertThatThrownBy(() -> system.fetch("../../admin")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JiraTicketSystem(WebClient.builder(), jira.url(), "", "", Duration.ofSeconds(5)))
				.hasMessageContaining("token");
		assertThat(AdfText.toText(null)).isEmpty();
	}
}
