package io.agenticsdlc.adapter.out.git;

import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.ProjectConfig;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Working copies managed with JGit on the host. The work tree ({@code <run>/repo}) is mounted into the sandbox; the
 * git directory ({@code <run>/git}) is not, and hooks are disabled, so files the agent writes cannot execute on the
 * host where credentials live. Base branch and commit are recorded in the git config to keep {@link #checkout}
 * idempotent.
 */
public class JGitRepositoryCheckout implements RepositoryCheckout {

	private static final Logger log = LoggerFactory.getLogger(JGitRepositoryCheckout.class);
	private static final String SECTION = "agentic";
	private static final int MAX_INSTRUCTIONS_CHARS = 32_000;
	private static final int MAX_CONFIG_BYTES = 16_384;

	private final WorkspacePaths paths;
	private final Map<String, String> tokensByHost;
	private final Map<String, String> mirrors;
	private final int cloneDepth;

	public JGitRepositoryCheckout(WorkspacePaths paths, Map<String, String> tokensByHost, Map<String, String> mirrors,
			int cloneDepth) {
		this.paths = paths;
		this.tokensByHost = Map.copyOf(tokensByHost);
		this.mirrors = Map.copyOf(mirrors);
		this.cloneDepth = cloneDepth;
	}

	@Override
	public Mono<CheckoutInfo> checkout(RunView view) {
		return Mono.fromCallable(() -> checkoutBlocking(view)).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<String> diff(UUID runId) {
		return Mono.fromCallable(() -> diffBlocking(runId)).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<Void> remove(UUID runId) {
		return Mono.<Void>fromRunnable(() -> deleteRecursively(paths.runDir(runId)))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private CheckoutInfo checkoutBlocking(RunView view) throws IOException, GitAPIException {
		UUID runId = view.run().id();
		Path repoDir = paths.repo(runId);
		Path gitDir = paths.gitDir(runId);
		String workBranch = "agent/" + runId;

		if (!Files.isRegularFile(gitDir.resolve("config")) || readConfig(gitDir, "baseCommit") == null) {
			deleteRecursively(paths.runDir(runId));
			Files.createDirectories(repoDir);
			String url = rewrite(view.task().repository().cloneUrl().toString());
			log.info("cloning {} for run {}", redact(url), runId);
			CloneCommand clone = Git.cloneRepository()
					.setURI(url)
					.setDirectory(repoDir.toFile())
					.setGitDir(gitDir.toFile())
					.setCloneAllBranches(false)
					.setCredentialsProvider(credentials(view.task().repository().kind(), url));
			if (view.task().baseBranch() != null) {
				clone.setBranch(view.task().baseBranch());
			}
			if (cloneDepth > 0) {
				clone.setDepth(cloneDepth);
			}
			try (Git git = clone.call()) {
				Repository repository = git.getRepository();
				String baseBranch = repository.getBranch();
				ObjectId head = repository.resolve("HEAD");
				StoredConfig config = repository.getConfig();
				// Never run hooks, even if a later step writes some into the git directory.
				config.setString("core", null, "hooksPath", "/dev/null");
				config.setString(SECTION, null, "baseBranch", baseBranch);
				config.setString(SECTION, null, "baseCommit", head.name());
				config.save();
				git.checkout().setCreateBranch(true).setName(workBranch).call();
			}
		}

		try (Repository repository = open(runId)) {
			String baseBranch = repository.getConfig().getString(SECTION, null, "baseBranch");
			String baseCommit = repository.getConfig().getString(SECTION, null, "baseCommit");
			return new CheckoutInfo(baseBranch, baseCommit, workBranch, rootEntries(repoDir), projectConfig(repoDir),
					agentInstructions(repoDir));
		}
	}

	private String diffBlocking(UUID runId) throws IOException, GitAPIException {
		try (Repository repository = open(runId); Git git = new Git(repository)) {
			// Stage everything (respecting .gitignore) so new and deleted files show up; the index lives on the host.
			git.add().addFilepattern(".").call();
			git.add().addFilepattern(".").setUpdate(true).call();
			ObjectId base = ObjectId.fromString(repository.getConfig().getString(SECTION, null, "baseCommit"));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader);
					DiffFormatter formatter = new DiffFormatter(out)) {
				CanonicalTreeParser baseTree = new CanonicalTreeParser();
				baseTree.reset(reader, walk.parseCommit(base).getTree());
				formatter.setRepository(repository);
				formatter.format(formatter.scan(baseTree, new DirCacheIterator(repository.readDirCache())));
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}

	private Repository open(UUID runId) throws IOException {
		return new FileRepositoryBuilder()
				.setGitDir(paths.gitDir(runId).toFile())
				.setWorkTree(paths.repo(runId).toFile())
				.setMustExist(true)
				.build();
	}

	private static String readConfig(Path gitDir, String key) {
		try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build()) {
			return repository.getConfig().getString(SECTION, null, key);
		}
		catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}

	String rewrite(String url) {
		return mirrors.entrySet().stream()
				.filter(e -> url.startsWith(e.getKey()))
				.max(Comparator.comparingInt(e -> e.getKey().length()))
				.map(e -> e.getValue() + url.substring(e.getKey().length()))
				.orElse(url);
	}

	private CredentialsProvider credentials(ScmKind kind, String url) {
		String host = java.net.URI.create(url).getHost();
		String token = host == null ? null : tokensByHost.get(host);
		if (token == null || token.isBlank()) {
			return null;
		}
		String user = switch (kind) {
			case GITHUB -> "x-access-token";
			case GITLAB -> "oauth2";
			case BITBUCKET -> "x-token-auth";
			case AZURE_DEVOPS -> "pat";
		};
		return new UsernamePasswordCredentialsProvider(user, token);
	}

	private static Set<String> rootEntries(Path repoDir) throws IOException {
		try (Stream<Path> entries = Files.list(repoDir)) {
			return entries.map(p -> p.getFileName().toString()).filter(name -> !name.equals(".git"))
					.collect(Collectors.toUnmodifiableSet());
		}
	}

	static ProjectConfig projectConfig(Path repoDir) {
		Path file = repoDir.resolve(".agentic-sdlc.yml");
		try {
			if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_CONFIG_BYTES) {
				return null;
			}
			LoaderOptions options = new LoaderOptions();
			options.setAllowDuplicateKeys(false);
			options.setMaxAliasesForCollections(10);
			Object parsed = new Yaml(new SafeConstructor(options)).load(Files.readString(file));
			if (!(parsed instanceof Map<?, ?> map)) {
				return null;
			}
			return new ProjectConfig(string(map, "image"), string(map, "setup"), string(map, "build"),
					string(map, "test"));
		}
		catch (IOException | RuntimeException e) {
			log.warn("ignoring unreadable .agentic-sdlc.yml: {}", e.getMessage());
			return null;
		}
	}

	private static String string(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value == null ? null : value.toString();
	}

	private static String agentInstructions(Path repoDir) throws IOException {
		for (String name : List.of("AGENTS.md", "CLAUDE.md")) {
			Path file = repoDir.resolve(name);
			if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
				String text = Files.readString(file);
				return text.length() <= MAX_INSTRUCTIONS_CHARS ? text : text.substring(0, MAX_INSTRUCTIONS_CHARS);
			}
		}
		return null;
	}

	private static String redact(String url) {
		return url.replaceAll("//[^/@]+@", "//***@");
	}

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
