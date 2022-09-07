package io.quarkus.test.junit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import io.quarkus.launcher.RuntimeLaunchClassLoader;

public class QuarkusTestExtension implements QuarkusTestExtensionInterface {

    private static final String QUARKUS_TEST_EXTENSION_IMPL = "io.quarkus.test.junit.QuarkusTestExtensionImpl";

    private final QuarkusTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public QuarkusTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (QuarkusTestExtensionInterface) contextCl.loadClass(QUARKUS_TEST_EXTENSION_IMPL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + QUARKUS_TEST_EXTENSION_IMPL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + QUARKUS_TEST_EXTENSION_IMPL, e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.beforeEach(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.beforeTestExecution(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.afterTestExecution(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.afterEach(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.beforeAll(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.afterAll(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            return delegate.supportsParameter(parameterContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            return delegate.resolveParameter(parameterContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            return delegate.evaluateExecutionCondition(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public <T> T interceptTestClassConstructor(Invocation<T> invocation,
            ReflectiveInvocationContext<Constructor<T>> invocationContext, ExtensionContext extensionContext)
            throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            return delegate.interceptTestClassConstructor(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptBeforeAllMethod(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptBeforeEachMethod(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptTestMethod(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public <T> T interceptTestFactoryMethod(Invocation<T> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            return delegate.interceptTestFactoryMethod(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptTestTemplateMethod(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptDynamicTest(invocation, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, DynamicTestInvocationContext invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptDynamicTest(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptAfterEachMethod(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.interceptAfterAllMethod(invocation, invocationContext, extensionContext);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.handleBeforeAllMethodExecutionException(context, throwable);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.handleBeforeEachMethodExecutionException(context, throwable);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.handleAfterEachMethodExecutionException(context, throwable);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.handleAfterAllMethodExecutionException(context, throwable);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.testDisabled(context, reason);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.testSuccessful(context);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.testAborted(context, cause);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.testFailed(context, cause);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
