package io.agenticsdlc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgenticSdlcApplication {

	public static void main(String[] args) {
		configureReactor();
		SpringApplication.run(AgenticSdlcApplication.class, args);
	}

	/**
	 * Blocking work (model SDK streams, Docker, JGit) runs on {@code Schedulers.boundedElastic()}. Backed by
	 * virtual threads it is not capped at 10 x cores. Must be set before Reactor's Schedulers class loads.
	 */
	static void configureReactor() {
		System.setProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true");
	}

}
