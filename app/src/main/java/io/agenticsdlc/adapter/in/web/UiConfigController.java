package io.agenticsdlc.adapter.in.web;

import io.agenticsdlc.config.AgenticProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime settings for the web UI, so one UI build works in every environment. Contains no secrets. */
@RestController
class UiConfigController {

	private final Map<String, Object> config;

	UiConfigController(AgenticProperties properties) {
		AgenticProperties.Ui ui = properties.ui();
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("authMode", properties.security().local() ? "local" : ui.authMode());
		values.put("issuer", ui.issuer());
		values.put("clientId", ui.clientId());
		values.put("scope", ui.scope());
		values.put("rolesClaim", properties.security().rolesClaim());
		values.put("pullRequestPollSeconds", properties.scm().pullRequestPollInterval().toSeconds());
		this.config = Map.copyOf(values);
	}

	@GetMapping("/ui-config.json")
	ResponseEntity<Map<String, Object>> config() {
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(config);
	}
}
