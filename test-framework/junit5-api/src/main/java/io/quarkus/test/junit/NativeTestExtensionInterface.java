package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.api.extension.TestWatcher;

public interface NativeTestExtensionInterface extends BeforeEachCallback, AfterEachCallback, BeforeAllCallback,
        TestInstancePostProcessor, LifecycleMethodExecutionExceptionHandler, TestWatcher {

}
