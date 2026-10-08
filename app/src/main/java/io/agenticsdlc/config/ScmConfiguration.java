package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.scm.ScmHttp;
import io.agenticsdlc.adapter.out.scm.ScmPullRequests;
import io.agenticsdlc.core.application.RunCommands;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.core.scm.ChangePublisher;
import io.agenticsdlc.core.scm.PublishStage;
import io.agenticsdlc.core.scm.PullRequestTracker;
import io.agenticsdlc.core.scm.PullRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.Disposable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/** Publishing (push + pull request) and pull request tracking. Needs the workspace, which holds the working copy. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("agentic.sandbox.enabled")
class ScmConfiguration {

	@Bean
	ScmPullRequests pullRequests(WebClient.Builder webClient, AgenticProperties properties) {
		AgenticProperties.Scm scm = properties.scm();
		return new ScmPullRequests(new ScmHttp(webClient, scm.tokens(), scm.apiUrls(), java.time.Duration.ofSeconds(30)),
				scm.draftPullRequests());
	}

	@Bean
	PublishStage publishStage(ChangePublisher publisher, PullRequests pullRequests) {
		return new PublishStage(publisher, pullRequests);
	}

	@Bean
	PullRequestTracker pullRequestTracker(RunStore store, PullRequests pullRequests, RunCommands commands) {
		return new PullRequestTracker(store, pullRequests, commands);
	}

	@Bean
	PullRequestWatcher pullRequestWatcher(PullRequestTracker tracker, AgenticProperties properties) {
		return new PullRequestWatcher(tracker, properties.scm().pullRequestPollInterval());
	}

	/** Polls open pull requests; merged ones finish their run, closed ones cancel it. */
	static final class PullRequestWatcher implements SmartLifecycle {

		private static final Logger log = LoggerFactory.getLogger(PullRequestWatcher.class);

		private final PullRequestTracker tracker;
		private final java.time.Duration interval;
		private volatile Disposable schedule;

		PullRequestWatcher(PullRequestTracker tracker, java.time.Duration interval) {
			this.tracker = tracker;
			this.interval = interval;
		}

		@Override
		public void start() {
			schedule = Flux.interval(interval, interval)
					.concatMap(tick -> tracker.sweep().onErrorResume(e -> {
						log.warn("pull request sweep failed", e);
						return Flux.empty();
					}))
					.subscribe(runId -> log.info("run {} finished with its pull request", runId));
		}

		@Override
		public void stop() {
			Disposable current = schedule;
			if (current != null) {
				current.dispose();
			}
			schedule = null;
		}

		@Override
		public boolean isRunning() {
			return schedule != null && !schedule.isDisposed();
		}
	}
}
