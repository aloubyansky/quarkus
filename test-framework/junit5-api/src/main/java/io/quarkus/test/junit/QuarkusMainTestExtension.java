package io.quarkus.test.junit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

public class QuarkusMainTestExtension implements QuarkusMainTestExtensionInterface {

    private static final String QUARKUS_MAIN_TEST_EXTENSION_IMPL = "io.quarkus.test.junit.QuarkusMainTestExtensionImpl";

    private final QuarkusMainTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public QuarkusMainTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = originalCl;// new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (QuarkusMainTestExtensionInterface) contextCl.loadClass(QUARKUS_MAIN_TEST_EXTENSION_IMPL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + QUARKUS_MAIN_TEST_EXTENSION_IMPL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + QUARKUS_MAIN_TEST_EXTENSION_IMPL, e);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        delegate.beforeEach(context);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        delegate.afterEach(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return delegate.supportsParameter(parameterContext, extensionContext);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return delegate.resolveParameter(parameterContext, extensionContext);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        delegate.beforeAll(context);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        delegate.afterAll(context);
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        delegate.handleBeforeAllMethodExecutionException(context, throwable);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        delegate.handleBeforeEachMethodExecutionException(context, throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        delegate.handleAfterEachMethodExecutionException(context, throwable);
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        delegate.handleAfterAllMethodExecutionException(context, throwable);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        delegate.testDisabled(context, reason);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        delegate.testSuccessful(context);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        delegate.testAborted(context, cause);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        delegate.testFailed(context, cause);
    }

    @Override
    public <T> T interceptTestClassConstructor(Invocation<T> invocation,
            ReflectiveInvocationContext<Constructor<T>> invocationContext, ExtensionContext extensionContext)
            throws Throwable {
        return delegate.interceptTestClassConstructor(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        delegate.interceptBeforeAllMethod(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        delegate.interceptBeforeEachMethod(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        delegate.interceptTestMethod(invocation, invocationContext, extensionContext);
    }

    @Override
    public <T> T interceptTestFactoryMethod(Invocation<T> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        return delegate.interceptTestFactoryMethod(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        delegate.interceptTestTemplateMethod(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, ExtensionContext extensionContext) throws Throwable {
        delegate.interceptDynamicTest(invocation, extensionContext);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, DynamicTestInvocationContext invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        delegate.interceptDynamicTest(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        delegate.interceptAfterEachMethod(invocation, invocationContext, extensionContext);
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        delegate.interceptAfterAllMethod(invocation, invocationContext, extensionContext);
    }
}
