package io.agenticsdlc.adapter.out.cli;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agenticsdlc.core.domain.RunEvent;
import io.agenticsdlc.core.domain.RunEventType;
import io.agenticsdlc.core.domain.RunView;
import io.agenticsdlc.core.engine.StageContext;
import io.agenticsdlc.core.port.RunStore;
import io.agenticsdlc.support.TestRepos;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/** A stage context of a fresh run whose events are kept for assertions. */
final class RecordingContext {

	final List<RunEvent> events = new CopyOnWriteArrayList<>();
	final StageContext context;

	RecordingContext() {
		this(TestRepos.runFor("https://github.com/acme/shop.git"));
	}

	RecordingContext(RunView view) {
		RunStore store = mock(RunStore.class);
		when(store.append(any(), anyString(), anyList())).thenAnswer(call -> {
			events.addAll(call.getArgument(2));
			return Mono.empty();
		});
		context = new StageContext(view, store, "worker", Clock.systemUTC());
	}

	List<RunEvent> of(RunEventType type) {
		return events.stream().filter(e -> e.type() == type).toList();
	}
}
