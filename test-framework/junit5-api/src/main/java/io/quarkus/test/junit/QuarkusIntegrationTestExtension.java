package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.ExtensionContext;

import io.quarkus.launcher.RuntimeLaunchClassLoader;

public class QuarkusIntegrationTestExtension implements QuarkusIntegrationTestExtensionInterface {

    private static final String QUARKUS_INTEGRATION_TEST_EXTENSION_INTERNAL = "io.quarkus.test.junit.QuarkusIntegrationTestExtensionInternal";

    private final QuarkusIntegrationTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public QuarkusIntegrationTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (QuarkusIntegrationTestExtensionInterface) contextCl
                    .loadClass(QUARKUS_INTEGRATION_TEST_EXTENSION_INTERNAL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + QUARKUS_INTEGRATION_TEST_EXTENSION_INTERNAL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + QUARKUS_INTEGRATION_TEST_EXTENSION_INTERNAL, e);
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
    public void beforeAll(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.beforeAll(context);
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.postProcessTestInstance(testInstance, context);
    }
}