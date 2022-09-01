package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import io.quarkus.launcher.RuntimeLaunchClassLoader;

public class QuarkusMainTestExtension implements QuarkusMainTestExtensionInterface {

    private static final String QUARKUS_MAIN_TEST_EXTENSION_INTERNAL = "io.quarkus.test.junit.QuarkusMainTestExtensionInternal";

    private final QuarkusMainTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public QuarkusMainTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (QuarkusMainTestExtensionInterface) contextCl.loadClass(QUARKUS_MAIN_TEST_EXTENSION_INTERNAL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + QUARKUS_MAIN_TEST_EXTENSION_INTERNAL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + QUARKUS_MAIN_TEST_EXTENSION_INTERNAL, e);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.beforeEach(context);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.afterEach(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Thread.currentThread().setContextClassLoader(contextCl);
        return delegate.supportsParameter(parameterContext, extensionContext);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Thread.currentThread().setContextClassLoader(contextCl);
        return delegate.resolveParameter(parameterContext, extensionContext);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.beforeAll(context);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.afterAll(context);
    }
}
