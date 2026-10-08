package io.agenticsdlc.core.workspace;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Validates paths supplied by the model: relative to the workspace, or absolute under {@code /workspace}, and never
 * escaping it with {@code ..}. Lexical only; symlinks are resolved inside the container, never on the host.
 */
public final class WorkspacePath {

	private WorkspacePath() {
	}

	/** Normalised path relative to the workspace root; {@code "."} for the root itself. */
	public static String relative(String input) {
		if (input == null || input.isBlank()) {
			return ".";
		}
		String trimmed = input.strip();
		if (trimmed.indexOf('\u0000') >= 0) {
			throw new IllegalArgumentException("path contains a NUL character");
		}
		if (trimmed.startsWith(Sandbox.WORKDIR + "/") || trimmed.equals(Sandbox.WORKDIR)) {
			trimmed = trimmed.substring(Sandbox.WORKDIR.length());
		}
		else if (trimmed.startsWith("/")) {
			throw new IllegalArgumentException("absolute paths must be inside " + Sandbox.WORKDIR + ": " + input);
		}
		while (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		Path normalized;
		try {
			normalized = Path.of(trimmed.isEmpty() ? "." : trimmed).normalize();
		}
		catch (InvalidPathException e) {
			throw new IllegalArgumentException("invalid path: " + input);
		}
		String result = normalized.toString().replace('\\', '/');
		if (result.equals("..") || result.startsWith("../")) {
			throw new IllegalArgumentException("path escapes the workspace: " + input);
		}
		if (result.isEmpty()) {
			return ".";
		}
		// A leading dash would be read as an option by find, grep, cat and friends.
		return result.startsWith("-") ? "./" + result : result;
	}

	/** Single-quotes a value for POSIX {@code sh}. */
	public static String shellQuote(String value) {
		return "'" + value.replace("'", "'\"'\"'") + "'";
	}
}
