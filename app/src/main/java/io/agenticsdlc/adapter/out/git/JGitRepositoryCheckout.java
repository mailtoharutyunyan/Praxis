package io.agenticsdlc.adapter.out.git;

import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.scm.ChangePublisher;
import io.agenticsdlc.core.workspace.CheckoutInfo;
import io.agenticsdlc.core.workspace.NestedRepositoryException;
import io.agenticsdlc.core.workspace.ProjectConfig;
import io.agenticsdlc.core.workspace.RepositoryCheckout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
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
 * idempotent; the base commit is written last and marks a complete checkout.
 * <p>
 * Build settings ({@code .agentic-sdlc.yml}), agent instructions and the root listing are read from the base commit,
 * never the work tree, so the agent cannot change its own image, test command or reviewer guidance.
 */
public class JGitRepositoryCheckout implements RepositoryCheckout, ChangePublisher {

	private static final Logger log = LoggerFactory.getLogger(JGitRepositoryCheckout.class);
	private static final String SECTION = "agentic";
	private static final int MAX_INSTRUCTIONS_BYTES = 32_000;
	private static final int MAX_CONFIG_BYTES = 16_384;

	private final WorkspacePaths paths;
	private final Map<String, String> tokensByHost;
	private final Map<String, String> mirrors;
	private final int cloneDepth;
	private final PersonIdent author;

	public JGitRepositoryCheckout(WorkspacePaths paths, Map<String, String> tokensByHost, Map<String, String> mirrors,
			int cloneDepth) {
		this(paths, tokensByHost, mirrors, cloneDepth, "Agentic SDLC", "agentic-sdlc@noreply.invalid");
	}

	public JGitRepositoryCheckout(WorkspacePaths paths, Map<String, String> tokensByHost, Map<String, String> mirrors,
			int cloneDepth, String authorName, String authorEmail) {
		this.paths = paths;
		this.tokensByHost = Map.copyOf(tokensByHost);
		this.mirrors = Map.copyOf(mirrors);
		this.cloneDepth = cloneDepth;
		this.author = new PersonIdent(authorName, authorEmail);
	}

	@Override
	public Mono<PushedBranch> commitAndPush(RunView view, String message) {
		return Mono.fromCallable(() -> commitAndPushBlocking(view, message)).subscribeOn(Schedulers.boundedElastic());
	}

	private PushedBranch commitAndPushBlocking(RunView view, String message) throws IOException, GitAPIException {
		UUID runId = view.run().id();
		try (Repository repository = open(runId); Git git = new Git(repository)) {
			String workBranch = "agent/" + runId;
			if (!workBranch.equals(repository.getBranch())) {
				throw new IllegalStateException("working copy of run " + runId + " is not on " + workBranch);
			}
			stageAll(git, repository);
			if (!git.status().call().isClean()) {
				PersonIdent now = new PersonIdent(author, java.time.Instant.now());
				git.commit().setMessage(message).setAuthor(now).setCommitter(now).setSign(false).setNoVerify(true).call();
			}
			String commit = repository.resolve("HEAD").name();
			String url = rewrite(view.task().repository().cloneUrl().toString());
			Iterable<org.eclipse.jgit.transport.PushResult> results = git.push()
					.setRemote(url)
					.setRefSpecs(new org.eclipse.jgit.transport.RefSpec("refs/heads/" + workBranch + ":refs/heads/" + workBranch))
					.setCredentialsProvider(credentials(view.task().repository().kind(), url))
					.call();
			for (org.eclipse.jgit.transport.PushResult result : results) {
				for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
					var status = update.getStatus();
					if (status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
							&& status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) {
						throw new IllegalStateException("push of " + workBranch + " was rejected: " + status
								+ (update.getMessage() == null ? "" : " (" + update.getMessage() + ")"));
					}
				}
			}
			log.info("pushed {} at {} for run {}", workBranch, commit, runId);
			return new PushedBranch(workBranch, repository.getConfig().getString(SECTION, null, "baseBranch"), commit);
		}
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
				config.save();
				git.checkout().setCreateBranch(true).setName(workBranch).call();
				// Last: a checkout interrupted before this point is redone from scratch.
				config.setString(SECTION, null, "baseCommit", head.name());
				config.save();
			}
		}

		try (Repository repository = open(runId); RevWalk walk = new RevWalk(repository)) {
			String baseBranch = repository.getConfig().getString(SECTION, null, "baseBranch");
			String baseCommit = repository.getConfig().getString(SECTION, null, "baseCommit");
			RevTree tree = walk.parseCommit(ObjectId.fromString(baseCommit)).getTree();
			String config = readBlob(repository, tree, ".agentic-sdlc.yml", MAX_CONFIG_BYTES, false);
			return new CheckoutInfo(baseBranch, baseCommit, workBranch, rootEntries(repository, tree),
					config == null ? null : projectConfig(config), agentInstructions(repository, tree));
		}
	}

	private String diffBlocking(UUID runId) throws IOException, GitAPIException {
		try (Repository repository = open(runId); Git git = new Git(repository)) {
			// Stage everything (respecting .gitignore) so new and deleted files show up; the index lives on the host.
			stageAll(git, repository);
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

	/**
	 * {@code git add -A}, refusing nested repositories: git would record them as gitlinks (submodule pointers), so
	 * their contents would silently be missing from the diff and the pushed branch.
	 */
	private static void stageAll(Git git, Repository repository) throws IOException, GitAPIException {
		git.add().addFilepattern(".").call();
		git.add().addFilepattern(".").setUpdate(true).call();
		Set<String> baseGitlinks = new HashSet<>();
		try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
			tree.addTree(walk.parseCommit(ObjectId.fromString(
					repository.getConfig().getString(SECTION, null, "baseCommit"))).getTree());
			tree.setRecursive(true);
			while (tree.next()) {
				if (tree.getFileMode(0) == FileMode.GITLINK) {
					baseGitlinks.add(tree.getPathString());
				}
			}
		}
		DirCache index = repository.readDirCache();
		for (int i = 0; i < index.getEntryCount(); i++) {
			DirCacheEntry entry = index.getEntry(i);
			if (entry.getFileMode() == FileMode.GITLINK && !baseGitlinks.contains(entry.getPathString())) {
				git.rm().setCached(true).addFilepattern(entry.getPathString()).call();
				throw new NestedRepositoryException(entry.getPathString());
			}
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

	private static Set<String> rootEntries(Repository repository, RevTree tree) throws IOException {
		Set<String> names = new HashSet<>();
		try (TreeWalk walk = new TreeWalk(repository)) {
			walk.addTree(tree);
			walk.setRecursive(false);
			while (walk.next()) {
				names.add(walk.getNameString());
			}
		}
		return Set.copyOf(names);
	}

	/**
	 * A regular file at the root of {@code tree}, decoded as UTF-8 (malformed bytes replaced); null if absent, not a
	 * regular file (symlinks are ignored), or larger than {@code maxBytes} unless {@code truncate}.
	 */
	static String readBlob(Repository repository, RevTree tree, String name, int maxBytes, boolean truncate)
			throws IOException {
		try (TreeWalk walk = TreeWalk.forPath(repository, name, tree)) {
			if (walk == null) {
				return null;
			}
			FileMode mode = walk.getFileMode(0);
			if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
				return null;
			}
			ObjectLoader loader = repository.open(walk.getObjectId(0));
			if (loader.getSize() > maxBytes && !truncate) {
				return null;
			}
			try (InputStream in = loader.openStream()) {
				return new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
			}
		}
	}

	static ProjectConfig projectConfig(String yaml) {
		try {
			LoaderOptions options = new LoaderOptions();
			options.setAllowDuplicateKeys(false);
			options.setMaxAliasesForCollections(10);
			Object parsed = new Yaml(new SafeConstructor(options)).load(yaml);
			if (!(parsed instanceof Map<?, ?> map)) {
				return null;
			}
			return new ProjectConfig(string(map, "image"), string(map, "setup"), string(map, "build"),
					string(map, "test"));
		}
		catch (RuntimeException e) {
			log.warn("ignoring unreadable .agentic-sdlc.yml: {}", e.getMessage());
			return null;
		}
	}

	private static String string(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value == null ? null : value.toString();
	}

	private static String agentInstructions(Repository repository, RevTree tree) throws IOException {
		for (String name : List.of("AGENTS.md", "CLAUDE.md")) {
			String text = readBlob(repository, tree, name, MAX_INSTRUCTIONS_BYTES, true);
			if (text != null) {
				return text;
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
