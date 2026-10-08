package io.agenticsdlc.config.connectors;

import io.agenticsdlc.config.AgenticProperties;
import io.agenticsdlc.config.connectors.ConnectorCatalog.Definition;
import io.agenticsdlc.config.connectors.ConnectorCatalog.Field;
import io.agenticsdlc.config.connectors.ConnectorStore.StoredConnector;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connectors as configured at runtime (ADR-0007): an in-memory, decrypted snapshot every instance refreshes on change
 * and periodically, with typed views that fall back to the application properties. Consumers read it at call time,
 * so a token saved in the UI is used by the next request.
 */
public final class ConnectorSettings {

	public static final String CONFIGURED = "CONFIGURED";
	public static final String SKIPPED = "SKIPPED";
	private static final TypeReference<Map<String, String>> SECRETS = new TypeReference<>() {
	};

	/** A connector with its decrypted secrets, keyed {@code field} or {@code list.itemKey.field}. */
	public record Connector(String id, String status, Map<String, Object> config, Map<String, String> secrets) {
		public String text(String field) {
			Object value = config.get(field);
			return value == null ? "" : String.valueOf(value).strip();
		}

		public String secret(String key) {
			return secrets.getOrDefault(key, "");
		}

		@SuppressWarnings("unchecked")
		public List<Map<String, Object>> items(String list) {
			return config.get(list) instanceof List<?> items ? (List<Map<String, Object>>) items : List.of();
		}
	}

	public record GitHost(String kind, String host, String token, String apiUrl, String organization) {
	}

	public record ModelChoice(String provider, String model, String apiKey, String baseUrl, String region,
			String deployment) {
	}

	public record SlackChoice(String botToken, String signingSecret, String defaultKind, String defaultRepository) {
	}

	public record FeedbackChoice(String mention, String githubSecret, String gitlabToken) {
	}

	private final ConnectorStore store;
	private final SecretBox box;
	private final JsonMapper json;
	private final AgenticProperties properties;
	private final Clock clock;
	private final AtomicReference<Map<String, Connector>> snapshot = new AtomicReference<>(Map.of());
	private volatile long version;

	public ConnectorSettings(ConnectorStore store, SecretBox box, JsonMapper json, AgenticProperties properties,
			Clock clock) {
		this.store = store;
		this.box = box;
		this.json = json;
		this.properties = properties;
		this.clock = clock;
	}

	/** Reloads the snapshot from the store. */
	public Mono<Void> refresh() {
		return store.connectors().collectList().doOnNext(stored -> {
			Map<String, Connector> next = new LinkedHashMap<>();
			for (StoredConnector c : stored) {
				Map<String, String> secrets = c.encryptedSecrets() == null ? Map.of()
						: json.readValue(box.decrypt(c.encryptedSecrets()), SECRETS);
				next.put(c.id(), new Connector(c.id(), c.status(), c.config(), secrets));
			}
			if (!next.equals(snapshot.get())) {
				snapshot.set(Map.copyOf(next));
				version++;
			}
		}).then();
	}

	/** Changes whenever the snapshot does; consumers that cache derived objects compare it. */
	public long version() {
		return version;
	}

	public Optional<Connector> get(String id) {
		return Optional.ofNullable(snapshot.get().get(id));
	}

	public Optional<Connector> configured(String id) {
		return get(id).filter(c -> CONFIGURED.equals(c.status()));
	}

	/**
	 * Validates against the catalog and stores the connector. Secret values left empty keep their stored value;
	 * secrets of removed list items are dropped.
	 */
	public Mono<Connector> save(String id, Map<String, Object> config, Map<String, String> secrets, String actor) {
		Definition definition = ConnectorCatalog.find(id)
				.orElseThrow(() -> new IllegalArgumentException("unknown connector " + id));
		Map<String, Object> plain = new LinkedHashMap<>();
		Map<String, String> merged = new LinkedHashMap<>();
		Map<String, String> stored = get(id).map(Connector::secrets).orElse(Map.of());
		Map<String, String> given = secrets == null ? Map.of() : secrets;
		List<String> problems = new ArrayList<>();
		for (Field field : definition.fields()) {
			Object value = config == null ? null : config.get(field.name());
			switch (field.type()) {
				case "secret" -> keepOrSet(field.name(), field, given, stored, merged, problems);
				case "list" -> {
					List<Map<String, Object>> items = new ArrayList<>();
					if (value instanceof List<?> list) {
						for (Object raw : list) {
							if (!(raw instanceof Map<?, ?> item)) {
								continue;
							}
							Map<String, Object> clean = new LinkedHashMap<>();
							String key = String.valueOf(item.get(field.keyField()) == null ? "" : item.get(field.keyField())).strip();
							if (key.isEmpty()) {
								problems.add(field.label() + ": every entry needs " + field.keyField());
								continue;
							}
							for (Field itemField : field.itemFields()) {
								if (itemField.type().equals("secret")) {
									keepOrSet(field.name() + "." + key + "." + itemField.name(), itemField, given, stored,
											merged, problems);
								}
								else {
									Object v = item.get(itemField.name());
									String text = v == null ? "" : String.valueOf(v).strip();
									if (itemField.required() && text.isEmpty()) {
										problems.add(field.label() + " " + key + ": " + itemField.label() + " is required");
									}
									clean.put(itemField.name(), text);
								}
							}
							items.add(clean);
						}
					}
					if (field.required() && items.isEmpty()) {
						problems.add(field.label() + ": add at least one");
					}
					plain.put(field.name(), items);
				}
				default -> {
					String text = value == null ? "" : String.valueOf(value).strip();
					if (text.isEmpty() && field.defaultValue() != null) {
						text = field.defaultValue();
					}
					if (field.required() && text.isEmpty()) {
						problems.add(field.label() + " is required");
					}
					if (field.type().equals("url") && !text.isEmpty() && !text.matches("https?://\\S+")) {
						problems.add(field.label() + " must be an http(s) URL");
					}
					if (field.type().equals("select") && !text.isEmpty() && !field.options().contains(text)) {
						problems.add(field.label() + " must be one of " + field.options());
					}
					plain.put(field.name(), text);
				}
			}
		}
		if (!problems.isEmpty()) {
			return Mono.error(new IllegalArgumentException(String.join("; ", problems)));
		}
		String sealed = merged.isEmpty() ? null : box.encrypt(json.writeValueAsString(merged));
		return store.save(new StoredConnector(id, CONFIGURED, plain, sealed, clock.instant(), actor))
				.then(refresh())
				.then(Mono.fromSupplier(() -> get(id).orElseThrow()));
	}

	private static void keepOrSet(String key, Field field, Map<String, String> given, Map<String, String> stored,
			Map<String, String> merged, List<String> problems) {
		String value = given.getOrDefault(key, "");
		if (value.isBlank()) {
			value = stored.getOrDefault(key, "");
		}
		if (!value.isBlank()) {
			merged.put(key, value.strip());
		}
		else if (field.required()) {
			problems.add(field.label() + " is required");
		}
	}

	/** Marks an optional connector as deliberately not used. */
	public Mono<Void> skip(String id, String actor) {
		Definition definition = ConnectorCatalog.find(id)
				.orElseThrow(() -> new IllegalArgumentException("unknown connector " + id));
		if (definition.required()) {
			return Mono.error(new IllegalArgumentException(definition.title() + " is required and cannot be skipped"));
		}
		return store.save(new StoredConnector(id, SKIPPED, Map.of(), null, clock.instant(), actor)).then(refresh());
	}

	public Mono<Void> remove(String id) {
		return store.delete(id).then(refresh());
	}

	// Typed views with property fallbacks

	public String publicUrl() {
		String url = configured(ConnectorCatalog.APP).map(c -> c.text("publicUrl")).orElse("");
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** The link prefix to a run in the UI: configured, else derived from the public URL; empty if neither. */
	public String runLinkBase(String configured) {
		if (configured != null && !configured.isBlank()) {
			return configured;
		}
		return publicUrl().isEmpty() ? "" : publicUrl() + "/#/runs/";
	}

	public List<GitHost> gitHosts() {
		return configured(ConnectorCatalog.GIT).map(c -> c.items("hosts").stream()
				.map(item -> new GitHost(String.valueOf(item.get("kind")), String.valueOf(item.get("host")),
						c.secret("hosts." + item.get("host") + ".token"), String.valueOf(item.getOrDefault("apiUrl", "")),
						String.valueOf(item.getOrDefault("organization", ""))))
				.toList()).orElse(List.of());
	}

	/** The access token for a code host: from the UI, else from {@code agentic.scm.tokens}. */
	public Optional<String> scmToken(String host) {
		Optional<String> configured = gitHosts().stream().filter(h -> h.host().equalsIgnoreCase(host))
				.map(GitHost::token).filter(t -> !t.isBlank()).findFirst();
		return configured.or(() -> Optional.ofNullable(properties.scm().tokens().get(host)).filter(t -> !t.isBlank()));
	}

	public Optional<String> scmApiUrl(String host) {
		Optional<String> configured = gitHosts().stream().filter(h -> h.host().equalsIgnoreCase(host))
				.map(GitHost::apiUrl).filter(u -> !u.isBlank()).findFirst();
		return configured.or(() -> Optional.ofNullable(properties.scm().apiUrls().get(host)));
	}

	/** Hosts tasks may use: the configured allow list plus every host given a token in the UI. */
	public Set<String> allowedHosts() {
		Set<String> hosts = new LinkedHashSet<>(properties.scm().allowedHosts());
		gitHosts().forEach(h -> hosts.add(h.host().toLowerCase(java.util.Locale.ROOT)));
		return hosts;
	}

	public Optional<ModelChoice> model() {
		return configured(ConnectorCatalog.MODELS).map(c -> new ModelChoice(c.text("provider"), c.text("model"),
				c.secret("apiKey"), c.text("baseUrl"), c.text("region"), c.text("deployment")));
	}

	public Optional<SlackChoice> slack() {
		return configured(ConnectorCatalog.SLACK).map(c -> new SlackChoice(c.secret("botToken"), c.secret("signingSecret"),
				c.text("defaultKind"), c.text("defaultRepository")));
	}

	/** Pull request feedback settings: the UI's values where set, else {@code agentic.scm.feedback}. */
	public FeedbackChoice feedback() {
		AgenticProperties.Feedback props = properties.scm().feedback();
		Optional<Connector> c = configured(ConnectorCatalog.WEBHOOKS);
		return new FeedbackChoice(
				c.map(x -> x.text("mention")).filter(v -> !v.isBlank()).orElse(props.mention()),
				c.map(x -> x.secret("githubSecret")).filter(v -> !v.isBlank()).orElse(props.githubSecret()),
				c.map(x -> x.secret("gitlabToken")).filter(v -> !v.isBlank()).orElse(props.gitlabToken()));
	}
}
