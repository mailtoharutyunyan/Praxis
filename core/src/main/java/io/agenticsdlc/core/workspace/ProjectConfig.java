package io.agenticsdlc.core.workspace;

/**
 * Optional per-repository settings from {@code .agentic-sdlc.yml}; any field may be null to keep the detected
 * default.
 *
 * <pre>
 * image: maven:3.9-eclipse-temurin-21
 * setup: ./mvnw -B -ntp dependency:go-offline
 * build: ./mvnw -B -ntp -DskipTests test-compile
 * test:  ./mvnw -B -ntp verify
 * </pre>
 */
public record ProjectConfig(String image, String setup, String build, String test) {
}
