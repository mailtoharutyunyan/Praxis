package io.agenticsdlc.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TaskTest {

	private static final RepositoryRef REPO = new RepositoryRef(ScmKind.GITHUB,
			URI.create("https://github.com/acme/shop.git"));

	private static Task task(String title, String description) {
		return new Task(UUID.randomUUID(), TaskOrigin.JIRA, "SHOP-42", title, description, REPO, null,
				Trust.UNTRUSTED, "alice", null, Instant.EPOCH);
	}

	@Test
	void stripsSurroundingWhitespace() {
		Task task = task("  Add search  ", "\nAs a user I want search.\n");
		assertThat(task.title()).isEqualTo("Add search");
		assertThat(task.description()).isEqualTo("As a user I want search.");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t\n" })
	void rejectsBlankTitle(String title) {
		assertThatThrownBy(() -> task(title, "body")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("title");
	}

	@Test
	void rejectsOversizedDescription() {
		String huge = "x".repeat(Task.MAX_DESCRIPTION_LENGTH + 1);
		assertThatThrownBy(() -> task("t", huge)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("description");
	}

	@Test
	void rejectsBlankIdempotencyKey() {
		assertThatThrownBy(() -> new Task(UUID.randomUUID(), TaskOrigin.PROMPT, null, "t", "d", REPO, null,
				Trust.TRUSTED, "bob", " ", Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("idempotencyKey");
	}

	@Test
	void everyOriginIsRepresentable() {
		for (TaskOrigin origin : TaskOrigin.values()) {
			Task task = new Task(UUID.randomUUID(), origin, null, "t", "d", REPO, "main", Trust.TRUSTED, "bob",
					"key-1", Instant.EPOCH);
			assertThat(task.origin()).isEqualTo(origin);
		}
	}
}
