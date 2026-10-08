package io.agenticsdlc.core.stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

	/**
	 * Gitignore-style patterns, relative to the repository root, for path-scoped edit permissions of an AI CLI
	 * (ADR-0008). Every path they match is a test by {@link #isTest}; they cover the common cases with exact case, so
	 * some tests (say, under {@code Test/}) are not matched and the CLI is refused there.
	 */
	public static List<String> globs() {
		List<String> globs = new ArrayList<>();
		for (String directory : List.of("test", "tests", "__tests__", "spec", "specs", "testing", "androidTest",
				"integrationTest", "e2e")) {
			globs.add("**/" + directory + "/**");
		}
		for (String suffix : List.of("Test", "Tests", "IT", "Spec")) {
			for (String extension : List.of("java", "kt", "kts", "scala", "groovy", "cs", "swift")) {
				globs.add("**/*" + suffix + "." + extension);
			}
		}
		for (String extension : List.of("go", "py", "rs", "exs", "ex", "dart", "cpp", "cc", "c")) {
			globs.add("**/*_test." + extension);
		}
		globs.add("**/test_*.py");
		for (String kind : List.of("test", "spec")) {
			for (String extension : List.of("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts")) {
				globs.add("**/*." + kind + "." + extension);
			}
		}
		globs.add("**/*_spec.rb");
		return List.copyOf(globs);
	}

	/**
	 * Files whose changes differ between two diffs of the same working copy against its base: changed, added, deleted
	 * or reverted in between, in order of appearance.
	 */
	public static List<String> changedBetween(String before, String after) {
		Map<String, String> earlier = sections(before);
		Map<String, String> later = sections(after);
		Set<String> changed = new LinkedHashSet<>();
		later.forEach((file, section) -> {
			if (!section.equals(earlier.get(file))) {
				changed.add(file);
			}
		});
		earlier.keySet().stream().filter(file -> !later.containsKey(file)).forEach(changed::add);
		return List.copyOf(changed);
	}

	/** Each file's part of a diff, keyed by the path in its {@code diff --git a/<path> b/<path>} header. */
	private static Map<String, String> sections(String diff) {
		Map<String, String> sections = new LinkedHashMap<>();
		String file = null;
		StringBuilder section = new StringBuilder();
		for (String line : Objects.requireNonNull(diff, "diff").lines().toList()) {
			if (line.startsWith("diff --git a/")) {
				if (file != null) {
					sections.put(file, section.toString());
				}
				// "a/<path> b/<path>" without renames: both halves have the same length, even if the path has spaces.
				String paths = line.substring("diff --git ".length());
				file = paths.substring(2, 2 + (paths.length() - 5) / 2);
				section.setLength(0);
			}
			section.append(line).append('\n');
		}
		if (file != null) {
			sections.put(file, section.toString());
		}
		return sections;
	}
}
