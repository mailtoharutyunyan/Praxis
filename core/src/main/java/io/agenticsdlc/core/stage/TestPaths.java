package io.agenticsdlc.core.stage;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises test files across common toolchains: anything under a {@code test}/{@code tests}/{@code __tests__}/
 * {@code spec} directory, or named like a test ({@code FooTest.java}, {@code foo_test.go}, {@code test_foo.py},
 * {@code foo.test.ts}, {@code foo.spec.js}, {@code foo_spec.rb}, {@code FooTests.cs}).
 */
public final class TestPaths {

	private static final List<String> DIRECTORIES = List.of("test", "tests", "__tests__", "spec", "specs", "testing",
			"androidtest", "integrationtest", "e2e");
	/** Case-sensitive, so {@code Commit.java} or {@code Contest.java} are not mistaken for tests. */
	private static final Pattern JVM_FILE = Pattern.compile(".*(Test|Tests|IT|Spec)\\.(java|kt|kts|scala|groovy|cs|swift)");
	private static final Pattern FILE = Pattern.compile(
			".*_test\\.(go|py|rs|exs|ex|dart|cpp|cc|c)"
					+ "|test_.*\\.py"
					+ "|.*\\.(test|spec)\\.(js|jsx|ts|tsx|mjs|cjs|mts|cts)"
					+ "|.*_spec\\.rb");

	private TestPaths() {
	}

	/** @param path relative to the repository root, {@code /}-separated */
	public static boolean isTest(String path) {
		String[] segments = path.split("/");
		for (int i = 0; i < segments.length - 1; i++) {
			if (DIRECTORIES.contains(segments[i].toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		String name = segments[segments.length - 1];
		return JVM_FILE.matcher(name).matches() || FILE.matcher(name.toLowerCase(Locale.ROOT)).matches();
	}

	/** Files a unified diff adds or changes ({@code +++ b/...} lines), in order. */
	public static List<String> changedFiles(String diff) {
		return diff.lines().filter(line -> line.startsWith("+++ b/")).map(line -> line.substring(6)).distinct().toList();
	}
}
