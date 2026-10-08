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
}
