package io.agenticsdlc.config;

import io.agenticsdlc.adapter.out.persistence.R2dbcCoordination;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Coordination between instances: node heartbeats for workspace affinity and leases for singleton jobs. */
@Configuration(proxyBeanMethods = false)
class ClusterConfiguration {

	/** Hands out leases owned by this process (node name plus pid, so two processes never share one). */
	record JobLeases(R2dbcCoordination coordination, String owner) {
		PeriodicJob.Lease forJob(String job) {
			return ttl -> coordination.tryAcquireJob(job, owner, ttl);
		}
	}

	@Bean
	JobLeases jobLeases(R2dbcCoordination coordination, NodeIdentity node) {
		return new JobLeases(coordination, node.node() + "/" + ProcessHandle.current().pid());
	}

	@Bean
	PeriodicJob nodeHeartbeat(R2dbcCoordination coordination, NodeIdentity node, AgenticProperties properties) {
		java.time.Duration interval = properties.worker().nodeTimeout().dividedBy(3);
		return new PeriodicJob("node heartbeat", interval, interval, () -> coordination.heartbeat(node.node()), null);
	}
}
