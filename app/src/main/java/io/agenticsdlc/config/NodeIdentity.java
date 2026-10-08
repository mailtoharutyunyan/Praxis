package io.agenticsdlc.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * This instance's stable name in the cluster. Run workspaces live on a node's disk, so the name must survive restarts
 * for a restarted node to keep its runs.
 */
public record NodeIdentity(String node) {

	public NodeIdentity {
		Objects.requireNonNull(node, "node");
		if (node.isBlank() || node.length() > 64) {
			throw new IllegalArgumentException("node id must be 1-64 characters: '" + node + "'");
		}
	}

	static NodeIdentity of(String configured) {
		if (configured != null && !configured.isBlank()) {
			return new NodeIdentity(configured.strip());
		}
		try {
			String host = InetAddress.getLocalHost().getHostName();
			return new NodeIdentity(host.length() <= 64 ? host : host.substring(0, 64));
		}
		catch (UnknownHostException e) {
			return new NodeIdentity("unknown-host");
		}
	}
}
