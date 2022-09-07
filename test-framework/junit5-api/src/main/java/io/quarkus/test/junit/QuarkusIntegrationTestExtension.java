package io.quarkus.test.junit;

import java.util.Optional;

import org.junit.jupiter.api.extension.ExtensionContext;

import io.quarkus.launcher.RuntimeLaunchClassLoader;

public class QuarkusIntegrationTestExtension implements QuarkusIntegrationTestExtensionInterface {

    private static final String QUARKUS_INTEGRATION_TEST_EXTENSION_IMPL = "io.quarkus.test.junit.QuarkusIntegrationTestExtensionImpl";

    private final QuarkusIntegrationTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public QuarkusIntegrationTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (QuarkusIntegrationTestExtensionInterface) contextCl.loadClass(QUARKUS_INTEGRATION_TEST_EXTENSION_IMPL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + QUARKUS_INTEGRATION_TEST_EXTENSION_IMPL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + QUARKUS_INTEGRATION_TEST_EXTENSION_IMPL, e);
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
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate.postProcessTestInstance(testInstance, context);
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

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        delegate.beforeTestExecution(context);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        delegate.afterTestExecution(context);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        delegate.afterAll(context);
    }
}
