package io.quarkus.test.junit;

import java.util.Optional;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

public class QuarkusMainIntegrationTestExtension implements QuarkusMainIntegrationTestExtensionInterface {

    private static final String QUARKUS_MAIN_INTEGRATION_TEST_EXTENSION_IMPL = "io.quarkus.test.junit.QuarkusMainIntegrationTestExtensionImpl";

    private final QuarkusMainIntegrationTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public QuarkusMainIntegrationTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = originalCl;// new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (QuarkusMainIntegrationTestExtensionInterface) contextCl
                    .loadClass(QUARKUS_MAIN_INTEGRATION_TEST_EXTENSION_IMPL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + QUARKUS_MAIN_INTEGRATION_TEST_EXTENSION_IMPL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + QUARKUS_MAIN_INTEGRATION_TEST_EXTENSION_IMPL, e);
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
