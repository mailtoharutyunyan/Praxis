package io.agenticsdlc.core.workspace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Optional per-repository settings from {@code .agentic-sdlc.yml}; any field may be null or empty to keep the
 * detected default (ADR-0006).
 *
 * <pre>
 * # one service at the root
 * image: maven:3.9-eclipse-temurin-21
 * setup: ./mvnw -B -ntp dependency:go-offline
 * build: ./mvnw -B -ntp -DskipTests test-compile
 * test:  ./mvnw -B -ntp verify
 *
 * # or several services (monorepo); each field but path is optional when the toolchain is detectable
 * services:
 *   - { name: orders, path: services/orders }
 *   - { name: web, path: apps/web, image: node:24-bookworm, test: npm test -- --ci }
 *
 * # containers the tests need, on a private network; the name is the host name
 * sidecars:
 *   - { name: postgres, image: postgres:16, env: { POSTGRES_PASSWORD: test }, ready: pg_isready -U postgres }
 * env:
 *   SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/postgres
 * </pre>
 */
public record ProjectConfig(String image, String setup, String build, String test, List<ServiceConfig> services,
		List<SidecarConfig> sidecars, Map<String, String> env) {

	public ProjectConfig {
		services = services == null ? List.of() : List.copyOf(services);
		sidecars = sidecars == null ? List.of() : List.copyOf(sidecars);
		env = env == null ? Map.of() : Map.copyOf(env);
	}

	public ProjectConfig(String image, String setup, String build, String test) {
		this(image, setup, build, test, List.of(), List.of(), Map.of());
	}

	/** A service of a monorepo at {@code path}; null fields keep what is detected from that directory. */
	public record ServiceConfig(String name, String path, String image, String setup, String build, String test) {
		public ServiceConfig {
			Objects.requireNonNull(path, "path");
		}
	}

	/**
	 * A container the tests depend on (database, broker, cache).
	 *
	 * @param ready optional command run inside the sidecar until it succeeds, e.g. {@code pg_isready -U postgres}
	 */
	public record SidecarConfig(String name, String image, Map<String, String> env, String ready) {
		public SidecarConfig {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(image, "image");
			env = env == null ? Map.of() : Map.copyOf(env);
		}
	}
}
