package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.api.extension.TestWatcher;

public interface QuarkusIntegrationTestExtensionInterface extends BeforeTestExecutionCallback, AfterTestExecutionCallback,
        BeforeEachCallback, AfterEachCallback, BeforeAllCallback,
        AfterAllCallback, TestInstancePostProcessor, LifecycleMethodExecutionExceptionHandler, TestWatcher {

}
