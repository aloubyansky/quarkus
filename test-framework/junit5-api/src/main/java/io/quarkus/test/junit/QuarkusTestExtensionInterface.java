package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestWatcher;

public interface QuarkusTestExtensionInterface extends BeforeEachCallback, BeforeTestExecutionCallback,
        AfterTestExecutionCallback, AfterEachCallback, BeforeAllCallback, InvocationInterceptor, AfterAllCallback,
        ParameterResolver, ExecutionCondition, LifecycleMethodExecutionExceptionHandler, TestWatcher {

}
