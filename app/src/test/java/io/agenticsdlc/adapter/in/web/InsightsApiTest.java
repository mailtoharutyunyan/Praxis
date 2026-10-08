package io.agenticsdlc.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import io.agenticsdlc.TestcontainersConfigurationAccess;
import io.agenticsdlc.core.insights.DailyCount;
import io.agenticsdlc.core.insights.InsightsQueries;
import io.agenticsdlc.core.insights.InsightsStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The insights endpoint over fixed figures: shape, defaults, validation and security. */
@Import({ TestcontainersConfigurationAccess.class, InsightsApiTest.FixedFigures.class })
@SpringBootTest(properties = { "agentic.worker.enabled=false", "agentic.sandbox.enabled=false" })
class InsightsApiTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedFigures {
		@Bean
		@Primary
		InsightsQueries fixedInsightsQueries(Clock clock) {
			return new InsightsQueries(new InsightsStore() {
				@Override
				public Mono<Totals> totals(Instant from, Instant to) {
					return Mono.just(new Totals(12, 6, 2, 0, 1_500_000, 9876));
				}

				@Override
				public Flux<Double> minutesToPullRequest(Instant from, Instant to) {
					return Flux.fromIterable(List.of(40.0, 10.0, 30.0, 20.0));
				}

				@Override
				public Flux<DailyCount> runsPerDay(Instant from, Instant to) {
					return Flux.just(new DailyCount(LocalDate.ofInstant(to, ZoneOffset.UTC), 3));
				}
			}, clock);
		}
	}

	@Autowired
	ApplicationContext context;

	WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient()
				.responseTimeout(Duration.ofSeconds(20)).build();
	}

	private static JwtMutator user(String subject, String... roles) {
		return mockJwt().jwt(jwt -> jwt.subject(subject))
				.authorities(java.util.Arrays.stream(roles)
						.map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())).toList());
	}

	@Test
	void viewerGetsEveryFigure() {
		client.mutateWith(user("v", "viewer")).get().uri("/api/v1/insights?days=7").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.days").isEqualTo(7)
				.jsonPath("$.from").exists()
				.jsonPath("$.to").exists()
				.jsonPath("$.runsStarted").isEqualTo(12)
				.jsonPath("$.finished.DONE").isEqualTo(6)
				.jsonPath("$.finished.FAILED").isEqualTo(2)
				.jsonPath("$.finished.CANCELLED").isEqualTo(0)
				.jsonPath("$.successRate").isEqualTo(0.75)
				.jsonPath("$.minutesToPullRequest.median").isEqualTo(25.0)
				.jsonPath("$.minutesToPullRequest.p90").isEqualTo(37.0)
				.jsonPath("$.minutesToPullRequest.count").isEqualTo(4)
				.jsonPath("$.costUsd").isEqualTo(1.5)
				.jsonPath("$.totalTokens").isEqualTo(9876)
				.jsonPath("$.runsPerDay.length()").isEqualTo(7)
				.jsonPath("$.runsPerDay[0].count").isEqualTo(0)
				.jsonPath("$.runsPerDay[0].date")
				.value(date -> assertThat((String) date).matches("\\d{4}-\\d{2}-\\d{2}"))
				.jsonPath("$.runsPerDay[6].count").isEqualTo(3);
	}

	@Test
	void everyRoleMayReadInsights() {
		for (String role : List.of("operator", "approver", "admin")) {
			client.mutateWith(user(role, role)).get().uri("/api/v1/insights?days=1").exchange()
					.expectStatus().isOk()
					.expectBody().jsonPath("$.runsPerDay.length()").isEqualTo(1);
		}
	}

	@Test
	void daysDefaultsToThirty() {
		client.mutateWith(user("v", "viewer")).get().uri("/api/v1/insights").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.days").isEqualTo(30)
				.jsonPath("$.runsPerDay.length()").isEqualTo(30);
	}

	@Test
	void badDaysAreProblemDetails() {
		for (String days : List.of("0", "366", "abc")) {
			client.mutateWith(user("v", "viewer")).get().uri("/api/v1/insights?days=" + days).exchange()
					.expectStatus().isBadRequest()
					.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
		}
		client.mutateWith(user("v", "viewer")).get().uri("/api/v1/insights?days=365").exchange()
				.expectStatus().isOk();
	}

	@Test
	void requiresAToken() {
		client.get().uri("/api/v1/insights").exchange().expectStatus().isUnauthorized();
	}
}
