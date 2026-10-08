package io.agenticsdlc.core.stage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TestPathsTest {

	@ParameterizedTest
	@ValueSource(strings = { "src/test/java/a/FooTest.java", "app/FooTests.java", "x/FooIT.java", "tests/test_api.py",
			"pkg/api_test.go", "web/src/App.test.tsx", "web/a.spec.js", "spec/models/user_spec.rb", "__tests__/x.js",
			"Shop.Tests/OrderTests.cs", "lib/foo_test.py", "e2e/login.ts" })
	void recognisesTests(String path) {
		assertThat(TestPaths.isTest(path)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "src/main/java/a/Foo.java", "pom.xml", "contest.py", "latest.go", "attestation.ts",
			"src/Testing.java", "README.md", "hello.txt", "src/main/java/Commit.java", "src/Contest.java", "Edit.kt",
			"Audit.cs" })
	void rejectsCode(String path) {
		assertThat(TestPaths.isTest(path)).isFalse();
	}

	@Test
	void changedFilesComeFromTheDiffHeaders() {
		String diff = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n--- /dev/null\n+++ b/tests/y.sh\n";
		assertThat(TestPaths.changedFiles(diff)).containsExactly("x", "tests/y.sh");
	}

	/** Each glob, with its wildcards filled in, names a test: permission rules never allow more than the check does. */
	@Test
	void globsOnlyMatchTests() {
		assertThat(TestPaths.globs()).hasSizeGreaterThan(50).allSatisfy(glob -> {
			String example = glob.replace("**/", "src/deep/").replace("/**", "/sub/File.java").replace("*", "Some");
			assertThat(TestPaths.isTest(example)).as(glob + " -> " + example).isTrue();
		});
		assertThat(TestPaths.globs()).contains("**/test/**", "**/*Test.java", "**/*_test.go", "**/test_*.py",
				"**/*.spec.ts", "**/*_spec.rb");
	}

	@Test
	void changedBetweenComparesEachFilesPartOfTheDiff() {
		String app = "diff --git a/src/App.java b/src/App.java\n--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-a\n+b\n";
		String spaced = "diff --git a/my dir/a b.txt b/my dir/a b.txt\n--- a/my dir/a b.txt\n+++ b/my dir/a b.txt\n+x\n";
		String test = "diff --git a/tests/t.sh b/tests/t.sh\nnew file mode 100644\n--- /dev/null\n+++ b/tests/t.sh\n+ok\n";
		String appAgain = app.replace("+b", "+c");

		assertThat(TestPaths.changedBetween("", app + test)).containsExactly("src/App.java", "tests/t.sh");
		assertThat(TestPaths.changedBetween(app, app + test)).containsExactly("tests/t.sh");
		assertThat(TestPaths.changedBetween(app + spaced, appAgain + spaced)).containsExactly("src/App.java");
		assertThat(TestPaths.changedBetween(app + spaced, app)).containsExactly("my dir/a b.txt");
		assertThat(TestPaths.changedBetween(app, app)).isEmpty();
	}
}
