package io.agenticsdlc.adapter.out.docker;

/**
 * Collects command output up to a limit, keeping the first quarter and the most recent three quarters. Build tools
 * print the failure at the end, so the tail matters most.
 */
final class BoundedOutput {

	private final int headLimit;
	private final int tailLimit;
	private final StringBuilder head = new StringBuilder();
	private final StringBuilder tail = new StringBuilder();
	private long dropped;

	BoundedOutput(int maxChars) {
		this.headLimit = maxChars / 4;
		this.tailLimit = maxChars - headLimit;
	}

	synchronized void append(String chunk) {
		int toHead = Math.min(chunk.length(), headLimit - head.length());
		if (toHead > 0) {
			head.append(chunk, 0, toHead);
		}
		if (toHead == chunk.length()) {
			return;
		}
		tail.append(chunk, Math.max(toHead, 0), chunk.length());
		int excess = tail.length() - tailLimit;
		if (excess > 0) {
			tail.delete(0, excess);
			dropped += excess;
		}
	}

	synchronized boolean truncated() {
		return dropped > 0;
	}

	@Override
	public synchronized String toString() {
		if (dropped == 0) {
			return head.toString() + tail;
		}
		return head + "\n… [" + dropped + " characters omitted] …\n" + tail;
	}
}
