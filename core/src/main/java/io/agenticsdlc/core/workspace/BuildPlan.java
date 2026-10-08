package io.agenticsdlc.core.workspace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * How to build and test a working copy that may hold several services (ADR-0006): its components, each a directory
 * with its own toolchain, plus sidecar containers and environment for the builds.
 */
public record BuildPlan(List<Component> components, List<ProjectConfig.SidecarConfig> sidecars, Map<String, String> env) {

	/** Directories never scanned for services. */
	private static final Set<String> SKIPPED = Set.of("node_modules", "target", "build", "dist", "out", "vendor",
			"third_party", ".git", ".github", ".idea", ".vscode", "docs", "doc", "examples", "test", "tests",
			"__tests__", ".repos");
	private static final int MAX_DEPTH = 3;
	private static final Pattern NAME = Pattern.compile("[^a-z0-9-]+");

	public BuildPlan {
		if (components.isEmpty()) {
			throw new IllegalArgumentException("a build plan needs at least one component");
		}
		components = List.copyOf(components);
		sidecars = List.copyOf(sidecars);
		env = Map.copyOf(env);
	}

	/**
	 * A service: its directory ({@code .} for the repository root) and toolchain.
	 *
	 * @param name unique within the plan; lower case letters, digits and dashes
	 */
	public record Component(String name, String path, BuildProfile profile) {

		/** {@code command} run from this component's directory. */
		public String inDirectory(String command) {
			return path.equals(".") ? command : "cd " + WorkspacePath.shellQuote(path) + " && " + command;
		}

		boolean contains(String file) {
			return path.equals(".") || file.startsWith(path + "/");
		}
	}

	/** The component agents' plain commands run in: the root one, else the first. */
	public Component main() {
		return components.getFirst();
	}

	/**
	 * Components a change touches: those containing a changed file. A changed file outside every component (shared
	 * configuration, a library) affects all of them; no changed files affects none.
	 */
	public List<Component> affected(Collection<String> changedFiles) {
		Set<Component> hit = new java.util.LinkedHashSet<>();
		for (String file : changedFiles) {
			List<Component> owners = components.stream().filter(c -> c.contains(file)).toList();
			if (owners.isEmpty()) {
				return components;
			}
			// The most specific directory owns the file.
			hit.add(owners.stream().max(java.util.Comparator.comparingInt(c -> c.path().length())).orElseThrow());
		}
		return components.stream().filter(hit::contains).toList();
	}

	/**
	 * Detects the plan from the repository's files (relative paths, at least {@value #MAX_DEPTH} directories deep) and
	 * its {@code .agentic-sdlc.yml}. Empty when no component's toolchain can be determined.
	 */
	public static Optional<BuildPlan> detect(Set<String> files, ProjectConfig config) {
		ProjectConfig cfg = config == null ? new ProjectConfig(null, null, null, null) : config;
		Map<String, Set<String>> entries = entriesByDirectory(files);
		List<Component> components = new ArrayList<>();
		if (!cfg.services().isEmpty()) {
			for (ProjectConfig.ServiceConfig service : cfg.services()) {
				String path = WorkspacePath.relative(service.path());
				Optional<BuildProfile> profile = BuildProfile.detect(entries.getOrDefault(path, Set.of()),
						new ProjectConfig(service.image(), service.setup(), service.build(), service.test()));
				if (profile.isEmpty()) {
					return Optional.empty();
				}
				components.add(new Component(name(service.name() == null ? path : service.name()), path, profile.get()));
			}
		}
		else {
			Optional<BuildProfile> root = BuildProfile.detect(entries.getOrDefault(".", Set.of()), cfg);
			if (root.isPresent()) {
				components.add(new Component("app", ".", root.get()));
			}
			else {
				components.addAll(services(entries));
			}
		}
		if (components.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new BuildPlan(unique(components), cfg.sidecars(), cfg.env()));
	}

	/** Top-most directories, below the root, whose files reveal a toolchain. */
	private static List<Component> services(Map<String, Set<String>> entries) {
		List<Component> found = new ArrayList<>();
		for (Map.Entry<String, Set<String>> dir : new TreeMap<>(entries).entrySet()) {
			String path = dir.getKey();
			if (path.equals(".") || path.chars().filter(c -> c == '/').count() >= MAX_DEPTH || skipped(path)
					|| found.stream().anyMatch(c -> path.startsWith(c.path() + "/"))) {
				continue;
			}
			BuildProfile.detect(dir.getValue(), null).ifPresent(profile -> found.add(new Component(
					name(path.substring(path.lastIndexOf('/') + 1)), path, profile)));
		}
		return found;
	}

	private static boolean skipped(String path) {
		for (String segment : path.split("/")) {
			if (SKIPPED.contains(segment.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/** Names of the files and directories in each directory, keyed by the directory's path ({@code .} for the root). */
	static Map<String, Set<String>> entriesByDirectory(Set<String> files) {
		Map<String, Set<String>> entries = new LinkedHashMap<>();
		for (String file : files) {
			String[] parts = file.split("/");
			String dir = ".";
			for (String part : parts) {
				entries.computeIfAbsent(dir, d -> new HashSet<>()).add(part);
				dir = dir.equals(".") ? part : dir + "/" + part;
			}
		}
		return entries;
	}

	private static String name(String raw) {
		String name = NAME.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("-").replaceAll("^-+|-+$", "");
		return name.isEmpty() ? "service" : name.length() <= 40 ? name : name.substring(0, 40);
	}

	/** The last path segment when unique, else the whole path. */
	private static List<Component> unique(List<Component> components) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		components.forEach(c -> counts.merge(c.name(), 1, Integer::sum));
		return components.stream().map(c -> counts.get(c.name()) == 1 || c.path().equals(".") ? c
				: new Component(name(c.path()), c.path(), c.profile())).toList();
	}
}
