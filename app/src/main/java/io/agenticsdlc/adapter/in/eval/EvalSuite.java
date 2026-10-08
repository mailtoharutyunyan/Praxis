package io.agenticsdlc.adapter.in.eval;

import io.agenticsdlc.core.domain.RepositoryRef;
import io.agenticsdlc.core.domain.ScmKind;
import io.agenticsdlc.core.eval.EvalCase;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * An evaluation suite file:
 *
 * <pre>
 * name: shop-regressions
 * trials: 3
 * concurrency: 2
 * cases:
 *   - id: SHOP-123
 *     title: Search ignores accents
 *     description: |
 *       ...the original ticket text...
 *     repository: { kind: GITHUB, clone-url: https://github.com/acme/shop.git }
 *     ref: eval/SHOP-123-base          # branch or tag at the parent of the merged fix
 *     hidden-files-dir: SHOP-123/tests # copied into the workspace only for grading (relative to the suite file)
 *     hidden-files: { path/in/repo: "inline content" }
 *     fail-to-pass: ["./mvnw -B -ntp -q test -Dtest=SearchAccentTest"]
 *     pass-to-pass: ["./mvnw -B -ntp -q test"]
 * </pre>
 */
record EvalSuite(String name, int trials, int concurrency, List<EvalCase> cases) {

	@SuppressWarnings("unchecked")
	static EvalSuite load(Path file) throws IOException {
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		Map<String, Object> root = new Yaml(new SafeConstructor(options)).load(Files.readString(file));
		Path baseDir = file.toAbsolutePath().getParent();
		List<EvalCase> cases = new ArrayList<>();
		for (Map<String, Object> raw : (List<Map<String, Object>>) root.getOrDefault("cases", List.of())) {
			Map<String, Object> repo = (Map<String, Object>) raw.get("repository");
			Map<String, String> hidden = new LinkedHashMap<>();
			if (raw.get("hidden-files-dir") != null) {
				hidden.putAll(readTree(baseDir.resolve(raw.get("hidden-files-dir").toString()).normalize()));
			}
			((Map<String, Object>) raw.getOrDefault("hidden-files", Map.of()))
					.forEach((path, content) -> hidden.put(path, String.valueOf(content)));
			cases.add(new EvalCase(String.valueOf(raw.get("id")), String.valueOf(raw.get("title")),
					String.valueOf(raw.getOrDefault("description", raw.get("title"))),
					new RepositoryRef(ScmKind.valueOf(String.valueOf(repo.get("kind")).toUpperCase(Locale.ROOT)),
							URI.create(String.valueOf(repo.get("clone-url")))),
					raw.get("ref") == null ? null : raw.get("ref").toString(), hidden,
					strings(raw.get("fail-to-pass")), strings(raw.get("pass-to-pass"))));
		}
		return new EvalSuite(String.valueOf(root.getOrDefault("name", file.getFileName().toString())),
				((Number) root.getOrDefault("trials", 1)).intValue(), ((Number) root.getOrDefault("concurrency", 1)).intValue(),
				cases);
	}

	private static List<String> strings(Object value) {
		return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
	}

	private static Map<String, String> readTree(Path dir) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
				files.put(dir.relativize(path).toString().replace('\\', '/'), Files.readString(path));
			}
		}
		return files;
	}
}
