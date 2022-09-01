package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public interface QuarkusIntegrationTestExtensionInterface
        extends BeforeEachCallback, AfterEachCallback, BeforeAllCallback, TestInstancePostProcessor {

}
