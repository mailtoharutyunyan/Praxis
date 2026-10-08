package io.agenticsdlc.adapter.out.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.support.TestRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitRepositoryCheckoutTest {

	private static final String URL = "https://github.com/acme/demo.git";

	@TempDir
	Path tmp;

	private WorkspacePaths paths;
	private JGitRepositoryCheckout checkout;
	private RunView view;

	@BeforeEach
	void setUp() throws Exception {
		Path source = TestRepos.demoRepo(tmp.resolve("source"));
		paths = new WorkspacePaths(tmp.resolve("workspaces"));
		checkout = new JGitRepositoryCheckout(paths, Map.of("github.com", "secret-token"),
				Map.of(URL, source.toUri().toString()), 1);
		view = TestRepos.runFor(URL);
	}

	@Test
	void clonesIntoSeparateWorkTreeAndGitDirWithWorkBranch() throws Exception {
		CheckoutInfo info = checkout.checkout(view).block();

		assertThat(info.baseBranch()).isEqualTo("main");
		assertThat(info.workBranch()).isEqualTo("agent/" + view.run().id());
		assertThat(info.baseCommit()).hasSize(40);
		assertThat(info.rootEntries()).contains("hello.txt", ".agentic-sdlc.yml", "AGENTS.md").doesNotContain(".git");
		assertThat(info.projectConfig().image()).isEqualTo(TestRepos.ALPINE);
		assertThat(info.projectConfig().test()).isEqualTo("grep -q hello hello.txt");
		assertThat(info.agentInstructions()).startsWith("# Agent notes");

		Path repoDir = paths.repo(view.run().id());
		assertThat(Files.isDirectory(repoDir.resolve(".git"))).isFalse();
		try (Repository repository = new FileRepositoryBuilder().setGitDir(paths.gitDir(view.run().id()).toFile())
				.build()) {
			assertThat(repository.getBranch()).isEqualTo(info.workBranch());
			assertThat(repository.getConfig().getString("core", null, "hooksPath")).isEqualTo("/dev/null");
		}
	}

	@Test
	void secondCheckoutReusesWorkingCopyAndKeepsChanges() throws Exception {
		CheckoutInfo first = checkout.checkout(view).block();
		Files.writeString(paths.repo(view.run().id()).resolve("hello.txt"), "hello agent\n");
		CheckoutInfo second = checkout.checkout(view).block();

		assertThat(second.baseCommit()).isEqualTo(first.baseCommit());
		assertThat(Files.readString(paths.repo(view.run().id()).resolve("hello.txt"))).isEqualTo("hello agent\n");
	}

	@Test
	void diffShowsChangesAndNewFilesButNotIgnoredOnes() throws Exception {
		checkout.checkout(view).block();
		Path repoDir = paths.repo(view.run().id());
		assertThat(checkout.diff(view.run().id()).block()).isEmpty();

		Files.writeString(repoDir.resolve("hello.txt"), "hello agent\n");
		Files.writeString(repoDir.resolve("NEW.md"), "new file\n");
		Files.createDirectories(repoDir.resolve("out"));
		Files.writeString(repoDir.resolve("out/build.log"), "ignored\n");
		Files.delete(repoDir.resolve("AGENTS.md"));

		String diff = checkout.diff(view.run().id()).block();
		assertThat(diff).contains("-hello world", "+hello agent", "+++ b/NEW.md", "--- a/AGENTS.md")
				.doesNotContain("build.log");
	}

	@Test
	void symlinkedConfigIsIgnored() throws Exception {
		Path dir = tmp.resolve("symlinked");
		Files.createDirectories(dir);
		Path target = tmp.resolve("outside.yml");
		Files.writeString(target, "image: evil\n");
		Files.createSymbolicLink(dir.resolve(".agentic-sdlc.yml"), target);
		assertThat(JGitRepositoryCheckout.projectConfig(dir)).isNull();
	}

	@Test
	void mirrorRewriteUsesLongestPrefix() {
		JGitRepositoryCheckout rewriting = new JGitRepositoryCheckout(paths, Map.of(),
				Map.of("https://github.com/", "https://mirror/gh/", "https://github.com/acme/", "https://mirror/acme/"), 0);
		assertThat(rewriting.rewrite("https://github.com/acme/x.git")).isEqualTo("https://mirror/acme/x.git");
		assertThat(rewriting.rewrite("https://github.com/other/x.git")).isEqualTo("https://mirror/gh/other/x.git");
		assertThat(rewriting.rewrite("https://gitlab.com/x.git")).isEqualTo("https://gitlab.com/x.git");
	}

	@Test
	void removeDeletesEverything() {
		checkout.checkout(view).block();
		checkout.remove(view.run().id()).block();
		assertThat(paths.runDir(view.run().id())).doesNotExist();
		checkout.remove(view.run().id()).block();
	}
}
