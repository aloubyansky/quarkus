package io.quarkus.test.junit;

import java.util.Optional;

import org.junit.jupiter.api.extension.ExtensionContext;

public class NativeTestExtension implements NativeTestExtensionInterface {

    private static final String NATIVE_TEST_EXTENSION_IMPL = "io.quarkus.test.junit.NativeTestExtensionImpl";

    private final NativeTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public NativeTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = originalCl;// new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (NativeTestExtensionInterface) contextCl.loadClass(NATIVE_TEST_EXTENSION_IMPL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + NATIVE_TEST_EXTENSION_IMPL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + NATIVE_TEST_EXTENSION_IMPL, e);
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
    public void beforeAll(ExtensionContext context) throws Exception {
        delegate.beforeAll(context);
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        delegate.postProcessTestInstance(testInstance, context);
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
}
