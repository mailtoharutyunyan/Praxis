package io.agenticsdlc.adapter.out.git;

import io.agenticsdlc.config.WorkspacePaths;
import io.agenticsdlc.core.domain.RepositoryRef;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
	/** Where companion repositories' working copies live inside the primary one (ADR-0006). */
	static final String COMPANIONS = ".repos";
	private static final int MAX_INSTRUCTIONS_BYTES = 32_000;
	private static final int MAX_CONFIG_BYTES = 16_384;
	private static final int FILE_DEPTH = 4;
	private static final int MAX_FILES = 50_000;

	private final WorkspacePaths paths;
	private final Function<String, Optional<String>> tokens;
	private final Map<String, String> mirrors;
	private final int cloneDepth;
	private final PersonIdent author;

	public JGitRepositoryCheckout(WorkspacePaths paths, Map<String, String> tokensByHost, Map<String, String> mirrors,
			int cloneDepth) {
		this(paths, tokensByHost, mirrors, cloneDepth, "Agentic SDLC", "agentic-sdlc@noreply.invalid");
	}

	public JGitRepositoryCheckout(WorkspacePaths paths, Map<String, String> tokensByHost, Map<String, String> mirrors,
			int cloneDepth, String authorName, String authorEmail) {
		this(paths, tokenLookup(tokensByHost), mirrors, cloneDepth, authorName, authorEmail);
	}

	/** Tokens looked up per clone or push, so ones changed at runtime apply to the next operation. */
	public JGitRepositoryCheckout(WorkspacePaths paths, Function<String, Optional<String>> tokens,
			Map<String, String> mirrors, int cloneDepth, String authorName, String authorEmail) {
		this.paths = paths;
		this.tokens = tokens;
		this.mirrors = Map.copyOf(mirrors);
		this.cloneDepth = cloneDepth;
		this.author = new PersonIdent(authorName, authorEmail);
	}

	@Override
	public Mono<PushedBranch> commitAndPush(RunView view, String message) {
		return Mono.fromCallable(() -> commitAndPushBlocking(view, message)).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public Mono<List<RepositoryPush>> commitAndPushAll(RunView view, String message) {
		return Mono.fromCallable(() -> {
			List<RepositoryPush> pushes = new ArrayList<>();
			for (Tree tree : java.util.stream.Stream.concat(Stream.of(primary(view)), companions(view).stream()).toList()) {
				if (Files.isRegularFile(tree.gitDir().resolve("config")) && hasChanges(tree)) {
					pushes.add(new RepositoryPush(tree.alias(), tree.repository(), commitAndPush(view, tree, message)));
				}
			}
			return pushes;
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/** Uncommitted changes, or commits on the work branch beyond its base. */
	private boolean hasChanges(Tree tree) throws IOException, GitAPIException {
		try (Repository repository = open(tree); Git git = new Git(repository)) {
			stageAll(git, repository);
			String base = repository.getConfig().getString(SECTION, null, "baseCommit");
			return !git.status().call().isClean() || !repository.resolve("HEAD").name().equals(base);
		}
	}

	private PushedBranch commitAndPushBlocking(RunView view, String message) throws IOException, GitAPIException {
		return commitAndPush(view, primary(view), message);
	}

	private PushedBranch commitAndPush(RunView view, Tree tree, String message) throws IOException, GitAPIException {
		UUID runId = view.run().id();
		try (Repository repository = open(tree); Git git = new Git(repository)) {
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
			String url = rewrite(tree.repository().cloneUrl().toString());
			Iterable<org.eclipse.jgit.transport.PushResult> results = git.push()
					.setRemote(url)
					.setRefSpecs(new org.eclipse.jgit.transport.RefSpec("refs/heads/" + workBranch + ":refs/heads/" + workBranch))
					.setCredentialsProvider(credentials(tree.repository().kind(), url))
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
			log.info("pushed {} at {} to {} for run {}", workBranch, commit, redact(url), runId);
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
			clone(view, primary(view));
		}
		// Companion working copies live inside the primary one; keep them out of its commits.
		Path exclude = gitDir.resolve("info").resolve("exclude");
		Files.createDirectories(exclude.getParent());
		if (!Files.isRegularFile(exclude) || !Files.readString(exclude).contains("/" + COMPANIONS + "/")) {
			Files.writeString(exclude, "\n/" + COMPANIONS + "/\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.APPEND);
		}
		List<CheckoutInfo.Companion> companions = new ArrayList<>();
		for (Tree companion : companions(view)) {
			if (!Files.isRegularFile(companion.gitDir().resolve("config")) || readConfig(companion.gitDir(), "baseCommit") == null) {
				deleteRecursively(companion.workTree());
				deleteRecursively(companion.gitDir());
				clone(view, companion);
			}
			try (Repository repository = open(companion); RevWalk walk = new RevWalk(repository)) {
				String baseCommit = repository.getConfig().getString(SECTION, null, "baseCommit");
				RevTree tree = walk.parseCommit(ObjectId.fromString(baseCommit)).getTree();
				String config = readBlob(repository, tree, ".agentic-sdlc.yml", MAX_CONFIG_BYTES, false);
				companions.add(new CheckoutInfo.Companion(companion.alias(), companion.path(),
						companion.repository().cloneUrl().toString(),
						repository.getConfig().getString(SECTION, null, "baseBranch"), baseCommit, files(repository, tree),
						config == null ? null : projectConfig(config)));
			}
		}

		try (Repository repository = open(runId); RevWalk walk = new RevWalk(repository)) {
			String baseBranch = repository.getConfig().getString(SECTION, null, "baseBranch");
			String baseCommit = repository.getConfig().getString(SECTION, null, "baseCommit");
			RevTree tree = walk.parseCommit(ObjectId.fromString(baseCommit)).getTree();
			String config = readBlob(repository, tree, ".agentic-sdlc.yml", MAX_CONFIG_BYTES, false);
			return new CheckoutInfo(baseBranch, baseCommit, workBranch, rootEntries(repository, tree),
					files(repository, tree), config == null ? null : projectConfig(config),
					agentInstructions(repository, tree), companions);
		}
	}

	/** One repository of a run: the primary ({@code alias} null) or a companion (ADR-0006). */
	private record Tree(String alias, RepositoryRef repository, String baseBranch, Path workTree, Path gitDir) {
		String path() {
			return alias == null ? "." : COMPANIONS + "/" + alias;
		}
	}

	private Tree primary(RunView view) {
		UUID runId = view.run().id();
		return new Tree(null, view.task().repository(), view.task().baseBranch(), paths.repo(runId), paths.gitDir(runId));
	}

	private List<Tree> companions(RunView view) {
		UUID runId = view.run().id();
		return view.task().companions().stream().map(c -> new Tree(c.alias(), c.repository(), c.baseBranch(),
				paths.repo(runId).resolve(c.path()), paths.runDir(runId).resolve("git-repos").resolve(c.alias()))).toList();
	}

	/** Companions found on disk, for operations that only know the run id. */
	private List<Tree> companionsOnDisk(UUID runId) throws IOException {
		Path gitDirs = paths.runDir(runId).resolve("git-repos");
		if (!Files.isDirectory(gitDirs)) {
			return List.of();
		}
		try (Stream<Path> dirs = Files.list(gitDirs)) {
			return dirs.sorted().map(dir -> new Tree(dir.getFileName().toString(), null, null,
					paths.repo(runId).resolve(COMPANIONS).resolve(dir.getFileName().toString()), dir)).toList();
		}
	}

	/**
	 * Clones a repository of the run with a separate git directory, then creates the work branch: from the pushed
	 * branch if the run already pushed one (a revision after the workspace was lost), else from the base.
	 */
	private void clone(RunView view, Tree tree) throws IOException, GitAPIException {
		UUID runId = view.run().id();
		String workBranch = "agent/" + runId;
		Files.createDirectories(tree.workTree());
		String url = rewrite(tree.repository().cloneUrl().toString());
		log.info("cloning {} for run {}", redact(url), runId);
		CloneCommand clone = Git.cloneRepository()
				.setURI(url)
				.setDirectory(tree.workTree().toFile())
				.setGitDir(tree.gitDir().toFile())
				.setCloneAllBranches(false)
				.setCredentialsProvider(credentials(tree.repository().kind(), url));
		if (tree.baseBranch() != null) {
			clone.setBranch(tree.baseBranch());
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
			config.setString(SECTION, null, "cloneUrl", tree.repository().cloneUrl().toString());
			config.setString(SECTION, null, "scmKind", tree.repository().kind().name());
			config.save();
			ObjectId pushed = fetchWorkBranch(git, tree.repository(), url, workBranch);
			git.checkout().setCreateBranch(true).setName(workBranch)
					.setStartPoint(pushed == null ? head.name() : pushed.name()).call();
			// Last: a checkout interrupted before this point is redone from scratch.
			config.setString(SECTION, null, "baseCommit", head.name());
			config.save();
		}
	}

	/** The primary repository's diff, then each companion's with paths under its directory. */
	private String diffBlocking(UUID runId) throws IOException, GitAPIException {
		StringBuilder diff = new StringBuilder();
		try (Repository repository = open(runId)) {
			diff.append(diff(repository, ""));
		}
		for (Tree companion : companionsOnDisk(runId)) {
			try (Repository repository = open(companion)) {
				diff.append(diff(repository, companion.path() + "/"));
			}
		}
		return diff.toString();
	}

	private static String diff(Repository repository, String prefix) throws IOException, GitAPIException {
		try (Git git = new Git(repository)) {
			// Stage everything (respecting .gitignore) so new and deleted files show up; the index lives on the host.
			stageAll(git, repository);
			ObjectId base = ObjectId.fromString(repository.getConfig().getString(SECTION, null, "baseCommit"));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader);
					DiffFormatter formatter = new DiffFormatter(out)) {
				CanonicalTreeParser baseTree = new CanonicalTreeParser();
				baseTree.reset(reader, walk.parseCommit(base).getTree());
				formatter.setRepository(repository);
				formatter.setOldPrefix("a/" + prefix);
				formatter.setNewPrefix("b/" + prefix);
				formatter.format(formatter.scan(baseTree, new DirCacheIterator(repository.readDirCache())));
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}

	/** The tip of {@code workBranch} on the remote, fetched into the clone; null if the branch does not exist there. */
	private ObjectId fetchWorkBranch(Git git, RepositoryRef repositoryRef, String url, String workBranch) {
		String remoteRef = "refs/remotes/origin/" + workBranch;
		try {
			var fetch = git.fetch().setRemote(url)
					.setRefSpecs(new org.eclipse.jgit.transport.RefSpec("+refs/heads/" + workBranch + ":" + remoteRef))
					.setCredentialsProvider(credentials(repositoryRef.kind(), url));
			if (cloneDepth > 0) {
				fetch.setDepth(cloneDepth);
			}
			fetch.call();
			return git.getRepository().resolve(remoteRef);
		}
		catch (GitAPIException | IOException | RuntimeException e) {
			// Most runs have not pushed yet: the remote has no such branch.
			return null;
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

	private static Repository open(Tree tree) throws IOException {
		return new FileRepositoryBuilder().setGitDir(tree.gitDir().toFile()).setWorkTree(tree.workTree().toFile())
				.setMustExist(true).build();
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

	private static Function<String, Optional<String>> tokenLookup(Map<String, String> byHost) {
		Map<String, String> copy = Map.copyOf(byHost);
		return host -> Optional.ofNullable(copy.get(host));
	}

	private CredentialsProvider credentials(ScmKind kind, String url) {
		String host = java.net.URI.create(url).getHost();
		String token = host == null ? null : tokens.apply(host).orElse(null);
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

	/** Paths of the files up to {@value #FILE_DEPTH} directories deep (at most {@value #MAX_FILES}), for service detection. */
	static Set<String> files(Repository repository, RevTree tree) throws IOException {
		Set<String> files = new HashSet<>();
		try (TreeWalk walk = new TreeWalk(repository)) {
			walk.addTree(tree);
			walk.setRecursive(false);
			while (walk.next() && files.size() < MAX_FILES) {
				if (walk.isSubtree()) {
					if (walk.getDepth() < FILE_DEPTH) {
						walk.enterSubtree();
					}
				}
				else {
					files.add(walk.getPathString());
				}
			}
		}
		return Set.copyOf(files);
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
			List<ProjectConfig.ServiceConfig> services = maps(map.get("services")).stream()
					.map(s -> new ProjectConfig.ServiceConfig(string(s, "name"), Objects.requireNonNull(string(s, "path"),
							"every service needs a path"), string(s, "image"), string(s, "setup"), string(s, "build"),
							string(s, "test")))
					.toList();
			List<ProjectConfig.SidecarConfig> sidecars = maps(map.get("sidecars")).stream()
					.map(s -> new ProjectConfig.SidecarConfig(Objects.requireNonNull(string(s, "name"), "every sidecar needs a name"),
							Objects.requireNonNull(string(s, "image"), "every sidecar needs an image"), strings(s.get("env")),
							string(s, "ready")))
					.toList();
			return new ProjectConfig(string(map, "image"), string(map, "setup"), string(map, "build"),
					string(map, "test"), services, sidecars, strings(map.get("env")));
		}
		catch (RuntimeException e) {
			log.warn("ignoring unreadable .agentic-sdlc.yml: {}", e.getMessage());
			return null;
		}
	}

	private static List<Map<?, ?>> maps(Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException("expected a list, got " + value);
		}
		List<Map<?, ?>> maps = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> map)) {
				throw new IllegalArgumentException("expected a mapping, got " + item);
			}
			maps.add(map);
		}
		return maps;
	}

	private static Map<String, String> strings(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return Map.of();
		}
		Map<String, String> strings = new java.util.LinkedHashMap<>();
		map.forEach((k, v) -> strings.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
		return strings;
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
