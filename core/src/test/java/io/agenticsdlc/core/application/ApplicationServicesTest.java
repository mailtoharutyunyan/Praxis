package io.agenticsdlc.core.application;

import static io.agenticsdlc.core.support.Fixtures.CLOCK;
import static io.agenticsdlc.core.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agenticsdlc.core.domain.Gate;
import io.agenticsdlc.core.domain.GateDecision;
import io.agenticsdlc.core.domain.GatePolicy;
import io.agenticsdlc.core.domain.RiskLevel;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunState;
import io.agenticsdlc.core.domain.Trust;
import io.agenticsdlc.core.port.ConcurrentRunUpdateException;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.port.RunStore.Cursor;
import io.agenticsdlc.core.support.Fixtures;
import io.agenticsdlc.core.support.InMemoryRunStore;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ApplicationServicesTest {

	private final InMemoryRunStore store = new InMemoryRunStore(CLOCK);
	private final TaskIntake intake = new TaskIntake(store, CLOCK, UUID::randomUUID, Fixtures.REPOSITORIES);

	private UUID submit(String requester) {
		return intake.submit(Fixtures.prompt(requester)).block().view().run().id();
	}

	/** Puts a run directly into the given gate, bypassing the worker. */
	private Run parkAt(UUID runId, RiskLevel risk, Gate gate) {
		Run run = store.run(runId);
		Run next = run.transitionTo(RunState.TRIAGING, T0).triaged(risk, GatePolicy.forRisk(risk, Trust.TRUSTED), T0)
				.transitionTo(RunState.PREPARING_CONTEXT, T0).transitionTo(RunState.SPECIFYING, T0);
		if (gate == Gate.PUBLISH) {
			next = next.transitionTo(RunState.IMPLEMENTING, T0).transitionTo(RunState.VERIFYING, T0)
					.transitionTo(RunState.REVIEWING, T0);
		}
		return store.update(run, next.awaitApproval(gate, T0), List.of()).block();
	}

	@Nested
	class Intake {

		@Test
		void promptIsTrustedAndRecorded() {
			RunStore.Submission submission = intake.submit(Fixtures.prompt("alice")).block();
			assertThat(submission.created()).isTrue();
			assertThat(submission.view().task().trust()).isEqualTo(Trust.TRUSTED);
			assertThat(submission.view().run().state()).isEqualTo(RunState.RECEIVED);
			assertThat(store.allEvents(submission.view().run().id())).singleElement()
					.satisfies(e -> {
						assertThat(e.type()).isEqualTo(RunEventType.RUN_CREATED);
						assertThat(e.actor()).isEqualTo("alice");
					});
		}

		@Test
		void ticketTextIsUntrusted() {
			assertThat(intake.submit(Fixtures.jira("jira-webhook")).block().view().task().trust())
					.isEqualTo(Trust.UNTRUSTED);
		}

		@Test
		void sameKeySameRequesterIsIdempotent() {
			NewTask request = withKey(Fixtures.prompt("alice"), "k-1");
			RunStore.Submission first = intake.submit(request).block();
			RunStore.Submission again = intake.submit(request).block();
			assertThat(again.created()).isFalse();
			assertThat(again.view().run().id()).isEqualTo(first.view().run().id());

			RunStore.Submission otherUser = intake.submit(withKey(Fixtures.prompt("bob"), "k-1")).block();
			assertThat(otherUser.created()).isTrue();
		}

		@Test
		void repositoryHostMustBeAllowed() {
			NewTask internal = new NewTask(io.agenticsdlc.core.domain.TaskOrigin.JIRA, "X-1", "t", "d",
					new io.agenticsdlc.core.domain.RepositoryRef(io.agenticsdlc.core.domain.ScmKind.GITLAB,
							java.net.URI.create("https://gitlab.internal.corp/x.git")), null, "jira", null);
			StepVerifier.create(intake.submit(internal)).expectErrorMessage("repository host gitlab.internal.corp is not allowed")
					.verify();
		}

		@Test
		void invalidInputFailsTheMono() {
			NewTask blank = new NewTask(io.agenticsdlc.core.domain.TaskOrigin.PROMPT, null, " ", "d", Fixtures.REPO,
					null, "alice", null);
			StepVerifier.create(intake.submit(blank)).expectError(IllegalArgumentException.class).verify();
		}

		private NewTask withKey(NewTask t, String key) {
			return new NewTask(t.origin(), t.externalRef(), t.title(), t.description(), t.repository(), t.baseBranch(),
					t.requestedBy(), key);
		}
	}

	@Nested
	class Commands {

		private final RunCommands commands = new RunCommands(store, CLOCK, false);

		@Test
		void approvingSpecMovesToImplementingAndIsAudited() {
			UUID runId = submit("alice");
			parkAt(runId, RiskLevel.MEDIUM, Gate.SPEC);

			Run decided = commands.decide(runId, Gate.SPEC, GateDecision.APPROVE, "looks right", "bob").block();

			assertThat(decided.state()).isEqualTo(RunState.IMPLEMENTING);
			List<RunEvent> log = store.allEvents(runId);
			assertThat(log.get(log.size() - 2)).satisfies(e -> {
				assertThat(e.type()).isEqualTo(RunEventType.GATE_DECIDED);
				assertThat(e.actor()).isEqualTo("bob");
				assertThat(e.payload()).containsEntry("comment", "looks right");
			});
			assertThat(log.getLast().type()).isEqualTo(RunEventType.STATE_CHANGED);
		}

		@Test
		void decisionOnWrongGateIsRejected() {
			UUID runId = submit("alice");
			parkAt(runId, RiskLevel.MEDIUM, Gate.SPEC);
			StepVerifier.create(commands.decide(runId, Gate.PUBLISH, GateDecision.APPROVE, null, "bob"))
					.expectError(IllegalStateException.class).verify();
		}

		@Test
		void fourEyesRuleWhenEnabled() {
			RunCommands strict = new RunCommands(store, CLOCK, true);
			UUID runId = submit("alice");
			parkAt(runId, RiskLevel.LOW, Gate.PUBLISH);
			StepVerifier.create(strict.decide(runId, Gate.PUBLISH, GateDecision.APPROVE, null, "alice"))
					.expectError(SelfApprovalException.class).verify();
			assertThat(strict.decide(runId, Gate.PUBLISH, GateDecision.APPROVE, null, "bob").block().state())
					.isEqualTo(RunState.PUBLISHING);
		}

		@Test
		void cancelAndResume() {
			UUID cancelled = submit("alice");
			assertThat(commands.cancel(cancelled, "wrong repo", "alice").block().state())
					.isEqualTo(RunState.CANCELLED);
			StepVerifier.create(commands.cancel(cancelled, "again", "alice"))
					.expectError(IllegalStateException.class).verify();

			UUID escalated = submit("alice");
			Run run = store.run(escalated);
			store.update(run, run.transitionTo(RunState.TRIAGING, T0).escalate(T0), List.of()).block();
			assertThat(commands.resume(escalated, "alice").block().state()).isEqualTo(RunState.TRIAGING);
		}

		@Test
		void raiseRiskAddsGatesAndIsAudited() {
			UUID runId = submit("alice");
			parkAt(runId, RiskLevel.LOW, Gate.PUBLISH);
			Run raised = commands.raiseRisk(runId, RiskLevel.HIGH, "touches auth", "bob").block();
			assertThat(raised.gatePolicy().gates()).containsExactly(Gate.SPEC, Gate.IMPLEMENTATION, Gate.PUBLISH);
			assertThat(store.allEvents(runId).getLast().type()).isEqualTo(RunEventType.RISK_RAISED);
		}

		@Test
		void unknownRun() {
			StepVerifier.create(commands.cancel(UUID.randomUUID(), "x", "alice"))
					.expectError(RunNotFoundException.class).verify();
		}

		@Test
		void retriesOnceAfterConcurrentUpdate() {
			UUID runId = submit("alice");
			AtomicBoolean failedOnce = new AtomicBoolean();
			RunStore flaky = new DelegatingStore(store) {
				@Override
				public Mono<Run> update(Run current, Run next, List<RunEvent> events) {
					if (failedOnce.compareAndSet(false, true)) {
						return Mono.error(new ConcurrentRunUpdateException(current.id(), current.version()));
					}
					return super.update(current, next, events);
				}
			};
			assertThat(new RunCommands(flaky, CLOCK, false).cancel(runId, "x", "alice").block().state())
					.isEqualTo(RunState.CANCELLED);
			assertThat(failedOnce).isTrue();
		}
	}

	@Nested
	class Queries {

		private final RunQueries queries = new RunQueries(store, store, Duration.ofMillis(50));

		@Test
		void getAndListAndEvents() {
			UUID runId = submit("alice");
			assertThat(queries.get(runId).block().task().requestedBy()).isEqualTo("alice");
			assertThat(queries.list(Set.of(RunState.RECEIVED), null, 10).collectList().block()).hasSize(1);
			assertThat(queries.list(Set.of(RunState.DONE), null, 10).collectList().block()).isEmpty();
			assertThat(queries.events(runId, 0, 10).collectList().block()).hasSize(1);
			StepVerifier.create(queries.events(UUID.randomUUID(), 0, 10)).expectError(RunNotFoundException.class)
					.verify();
		}

		@Test
		void followReplaysThenStreamsLiveAndCompletesWhenTerminal() {
			UUID runId = submit("alice");
			RunCommands commands = new RunCommands(store, CLOCK, false);

			StepVerifier.create(queries.follow(runId, 0))
					.assertNext(e -> assertThat(e.type()).isEqualTo(RunEventType.RUN_CREATED))
					.then(() -> commands.cancel(runId, "stop", "alice").block())
					.assertNext(e -> assertThat(e.type()).isEqualTo(RunEventType.ERROR))
					.assertNext(e -> assertThat(e.payload()).containsEntry("to", "CANCELLED"))
					.expectComplete()
					.verify(Duration.ofSeconds(5));
		}

		@Test
		void followOfFinishedRunReplaysFromCursorAndCompletes() {
			UUID runId = submit("alice");
			new RunCommands(store, CLOCK, false).cancel(runId, "stop", "alice").block();
			StepVerifier.create(queries.follow(runId, 1)).expectNextCount(2).expectComplete().verify(Duration.ofSeconds(5));
		}
	}

	/** Forwards every call; tests override single methods to inject faults. */
	private static class DelegatingStore implements RunStore {
		private final RunStore delegate;

		DelegatingStore(RunStore delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Submission> submit(io.agenticsdlc.core.domain.Task task, Run run, List<RunEvent> events) {
			return delegate.submit(task, run, events);
		}

		@Override
		public Mono<io.agenticsdlc.core.domain.RunView> find(UUID runId) {
			return delegate.find(runId);
		}

		@Override
		public reactor.core.publisher.Flux<io.agenticsdlc.core.domain.RunView> list(Set<RunState> states,
				Cursor before, int limit) {
			return delegate.list(states, before, limit);
		}

		@Override
		public reactor.core.publisher.Flux<io.agenticsdlc.core.domain.RunView> listUpdatedSince(
				io.agenticsdlc.core.domain.TaskOrigin origin, Cursor after, int limit) {
			return delegate.listUpdatedSince(origin, after, limit);
		}

		@Override
		public Mono<Run> update(Run current, Run next, List<RunEvent> events) {
			return delegate.update(current, next, events);
		}

		@Override
		public Mono<Void> append(UUID runId, String leaseOwner, List<RunEvent> events) {
			return delegate.append(runId, leaseOwner, events);
		}

		@Override
		public reactor.core.publisher.Flux<RunEvent> events(UUID runId, long afterSeq, int limit) {
			return delegate.events(runId, afterSeq, limit);
		}

		@Override
		public reactor.core.publisher.Flux<RunEvent> latestEvents(UUID runId,
				java.util.Set<io.agenticsdlc.core.domain.RunEventType> types, int limit) {
			return delegate.latestEvents(runId, types, limit);
		}

		@Override
		public Mono<Run> claim(String owner, Duration lease) {
			return delegate.claim(owner, lease);
		}

		@Override
		public Mono<Boolean> renewLease(UUID runId, String owner, Duration lease) {
			return delegate.renewLease(runId, owner, lease);
		}

		@Override
		public Mono<Void> releaseLease(UUID runId, String owner) {
			return delegate.releaseLease(runId, owner);
		}
	}

	@org.junit.jupiter.api.Test
	void requestersFromOutsideSystemsAreRecognisedByTheirAliases() {
		assertThat(RunCommands.samePerson("alice", "alice", Set.of())).isTrue();
		assertThat(RunCommands.samePerson("jira:alice@acme.com", "0f3c-sub", Set.of("Alice@Acme.com"))).isTrue();
		assertThat(RunCommands.samePerson("jira:admin", "sub-1", Set.of("admin"))).isTrue();
		assertThat(RunCommands.samePerson("jira:5b10ac8d82e05b22cc7d4ef5", "sub-1", Set.of("bob@acme.com"))).isFalse();
		assertThat(RunCommands.samePerson("bob", "alice", Set.of("alice@acme.com"))).isFalse();
	}
}
