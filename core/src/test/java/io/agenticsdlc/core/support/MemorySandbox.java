package io.agenticsdlc.core.support;

import io.agenticsdlc.core.workspace.CommandResult;
import io.agenticsdlc.core.workspace.Sandbox;
import io.agenticsdlc.core.workspace.SandboxSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** In-memory sandbox: files in a map, commands answered by a configurable function. */
public final class MemorySandbox implements Sandbox {

	public final Map<String, String> files = new TreeMap<>();
	public final List<String> commands = new ArrayList<>();
	public Function<String, CommandResult> onExec = c -> new CommandResult(c, 0, "ok", false, false, Duration.ZERO);

	@Override
	public Mono<Void> start(UUID runId, SandboxSpec spec) {
		return Mono.empty();
	}

	@Override
	public Mono<CommandResult> exec(UUID runId, String command, Duration timeout) {
		commands.add(command);
		return Mono.fromSupplier(() -> onExec.apply(command));
	}

	@Override
	public Mono<String> readFile(UUID runId, String relativePath, int maxBytes) {
		String content = files.get(relativePath);
		if (content == null) {
			return Mono.error(new NoSuchFileException(relativePath));
		}
		if (content.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
			return Mono.error(new IllegalArgumentException(relativePath + " is larger than " + maxBytes + " bytes"));
		}
		return Mono.just(content);
	}

	@Override
	public Mono<Void> writeFile(UUID runId, String relativePath, String content) {
		files.put(relativePath, content);
		return Mono.empty();
	}

	@Override
	public Mono<Void> destroy(UUID runId) {
		return Mono.empty();
	}
}
