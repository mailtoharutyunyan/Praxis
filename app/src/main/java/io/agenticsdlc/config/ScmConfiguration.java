package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.scm.ScmHttp;
import io.agenticsdlc.adapter.out.scm.ScmPullRequests;
import io.agenticsdlc.config.connectors.ConnectorSettings;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.memory.RepoMemory;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.scm.ChangePublisher;
import io.agenticsdlc.core.scm.PublishStage;
import io.agenticsdlc.core.scm.PullRequestFeedback;
import io.agenticsdlc.core.scm.PullRequestTracker;
import io.agenticsdlc.core.scm.PullRequests;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Publishing (push + pull request) and pull request tracking. Needs the workspace, which holds the working copy. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("agentic.sandbox.enabled")
class ScmConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ScmConfiguration.class);

	@Bean
	ScmPullRequests pullRequests(WebClient.Builder webClient, AgenticProperties properties, ConnectorSettings connectors) {
		return new ScmPullRequests(new ScmHttp(webClient, connectors::scmToken, connectors::scmApiUrl,
				java.time.Duration.ofSeconds(30)), properties.scm().draftPullRequests());
	}

	@Bean
	PublishStage publishStage(ChangePublisher publisher, PullRequests pullRequests, RepositoryCheckout checkout) {
		return new PublishStage(publisher, pullRequests, checkout);
	}

	@Bean
	PullRequestFeedback pullRequestFeedback(RunStore store, RunCommands commands, PullRequests pullRequests,
			AgenticProperties properties, ConnectorSettings connectors) {
		AgenticProperties.Feedback feedback = properties.scm().feedback();
		return new PullRequestFeedback(store, commands, pullRequests, () -> connectors.feedback().mention(),
				feedback.maxCiFixes(), () -> connectors.runLinkBase(feedback.runLinkBase()));
	}

	@Bean
	PullRequestTracker pullRequestTracker(RunStore store, PullRequests pullRequests, RunCommands commands,
			RepoMemory memory, java.time.Clock clock, AgenticProperties properties) {
		return new PullRequestTracker(store, pullRequests, commands, properties.memory().enabled() ? memory : null, clock,
				properties.memory().retention())
				.onError((run, error) -> log.warn("cannot check the pull requests of run {}: {}", run, error.getMessage()));
	}

	/** Polls open pull requests; merged ones finish their run, closed ones cancel it. One instance at a time. */
	@Bean
	PeriodicJob pullRequestWatcher(PullRequestTracker tracker, AgenticProperties properties,
			ClusterConfiguration.JobLeases leases) {
		java.time.Duration interval = properties.scm().pullRequestPollInterval();
		return new PeriodicJob("pull request tracking", interval, java.time.Duration.ofMinutes(10),
				() -> tracker.sweep().doOnNext(runId -> log.info("run {} finished with its pull request", runId)),
				leases.forJob("pull-request-tracker"));
	}
}
