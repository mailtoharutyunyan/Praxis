package io.agenticsdlc;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Public handle on the package-private test configuration, for tests in sub-packages. */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration.class)
public class TestcontainersConfigurationAccess {
}
