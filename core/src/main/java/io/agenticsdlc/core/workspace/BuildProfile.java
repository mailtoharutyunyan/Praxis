package io.agenticsdlc.core.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How to build and test a repository inside the sandbox.
 *
 * @param tool detected toolchain, for display ({@code maven}, {@code gradle}, {@code npm}, …, or {@code custom})
 * @param setup one-time preparation such as dependency installation; null if none
 */
public record BuildProfile(String tool, String image, String setup, String build, String test) {

	public BuildProfile {
		Objects.requireNonNull(tool, "tool");
		Objects.requireNonNull(image, "image");
		Objects.requireNonNull(build, "build");
		Objects.requireNonNull(test, "test");
	}

	/** Commands for a full verification, in order. */
	public List<String> verifyCommands() {
		List<String> commands = new ArrayList<>();
		commands.add(build);
		if (!test.equals(build)) {
			commands.add(test);
		}
		return commands;
	}

	/**
	 * Detects the toolchain from root files, then applies {@code .agentic-sdlc.yml} overrides. Empty when nothing is
	 * recognised and the repository has no complete config of its own.
	 */
	public static Optional<BuildProfile> detect(Set<String> rootEntries, ProjectConfig config) {
		Optional<BuildProfile> detected = detectDefaults(rootEntries);
		if (config == null) {
			return detected;
		}
		if (detected.isEmpty()) {
			if (config.image() == null || config.build() == null || config.test() == null) {
				return Optional.empty();
			}
			return Optional.of(new BuildProfile("custom", config.image(), config.setup(), config.build(), config.test()));
		}
		BuildProfile base = detected.get();
		return Optional.of(new BuildProfile(base.tool(), orElse(config.image(), base.image()),
				orElse(config.setup(), base.setup()), orElse(config.build(), base.build()),
				orElse(config.test(), base.test())));
	}

	private static Optional<BuildProfile> detectDefaults(Set<String> root) {
		if (root.contains("pom.xml")) {
			String mvn = root.contains("mvnw") ? "./mvnw" : "mvn";
			return Optional.of(new BuildProfile("maven", "maven:3.9-eclipse-temurin-25", null,
					mvn + " -B -ntp -DskipTests test-compile", mvn + " -B -ntp verify"));
		}
		if (root.contains("build.gradle") || root.contains("build.gradle.kts")) {
			boolean wrapper = root.contains("gradlew");
			String gradle = wrapper ? "./gradlew" : "gradle";
			return Optional.of(new BuildProfile("gradle", wrapper ? "eclipse-temurin:25-jdk" : "gradle:jdk25", null,
					gradle + " --no-daemon assemble testClasses", gradle + " --no-daemon check"));
		}
		if (root.contains("package.json")) {
			if (root.contains("pnpm-lock.yaml")) {
				return Optional.of(new BuildProfile("pnpm", "node:24-bookworm",
						"corepack enable && pnpm install --frozen-lockfile", "pnpm run --if-present build", "pnpm test"));
			}
			if (root.contains("yarn.lock")) {
				return Optional.of(new BuildProfile("yarn", "node:24-bookworm",
						"corepack enable && yarn install --frozen-lockfile", "yarn run build --if-present", "yarn test"));
			}
			String install = root.contains("package-lock.json") ? "npm ci" : "npm install";
			return Optional.of(new BuildProfile("npm", "node:24-bookworm", install, "npm run build --if-present",
					"npm test"));
		}
		if (root.contains("go.mod")) {
			return Optional.of(new BuildProfile("go", "golang:1.25-bookworm", "go mod download", "go build ./...",
					"go test ./..."));
		}
		if (root.contains("pyproject.toml") || root.contains("requirements.txt")) {
			String install = root.contains("requirements.txt") ? "pip install -r requirements.txt" : "pip install -e .";
			return Optional.of(new BuildProfile("python", "python:3.13-bookworm", install + " && pip install pytest",
					"python -m compileall -q .", "python -m pytest"));
		}
		boolean dotnet = root.stream().anyMatch(f -> f.endsWith(".sln") || f.endsWith(".slnx") || f.endsWith(".csproj"));
		if (dotnet) {
			return Optional.of(new BuildProfile("dotnet", "mcr.microsoft.com/dotnet/sdk:10.0", "dotnet restore",
					"dotnet build --no-restore", "dotnet test --no-restore"));
		}
		return Optional.empty();
	}

	private static String orElse(String value, String fallback) {
		return value != null ? value : fallback;
	}
}
