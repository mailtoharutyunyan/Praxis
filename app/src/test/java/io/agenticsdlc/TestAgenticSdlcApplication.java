package io.agenticsdlc;

import org.springframework.boot.SpringApplication;

public class TestAgenticSdlcApplication {

	public static void main(String[] args) {
		SpringApplication.from(AgenticSdlcApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
