package io.agenticsdlc.support;

import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.Run;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.domain.Task;
import io.agenticsdlc.core.domain.TaskOrigin;
import io.agenticsdlc.core.domain.Trust;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Small local git repositories for adapter tests, reachable through an https URL rewritten by a mirror rule. */
public final class TestRepos {

	public static final String ALPINE = "alpine:3.22";

	private TestRepos() {
	}

	/** A repository whose build and test run in plain alpine: they pass while hello.txt says hello. */
	public static Path demoRepo(Path dir) throws IOException, GitAPIException {
		return createRepo(dir, Map.of(
				".agentic-sdlc.yml", "image: " + ALPINE + "\nbuild: test -f hello.txt\ntest: grep -q hello hello.txt\n",
				"hello.txt", "hello world\n",
				"AGENTS.md", "# Agent notes\nKeep it simple.\n",
				".gitignore", "out/\n"));
	}

	public static Path createRepo(Path dir, Map<String, String> files) throws IOException, GitAPIException {
		Files.createDirectories(dir);
		try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
			for (Map.Entry<String, String> file : files.entrySet()) {
				Path path = dir.resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.writeString(path, file.getValue());
			}
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial").setAuthor("test", "test@example.com")
					.setCommitter("test", "test@example.com").setSign(false).call();
		}
		return dir;
	}

	public static RunView runFor(String httpsUrl) {
		Instant now = Instant.now();
		Task task = new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "t", "d",
				new RepositoryRef(ScmKind.GITHUB, URI.create(httpsUrl)), null, Trust.TRUSTED, "tester", null, now);
		return new RunView(Run.start(UUID.randomUUID(), task.id(), now), task);
	}
}
