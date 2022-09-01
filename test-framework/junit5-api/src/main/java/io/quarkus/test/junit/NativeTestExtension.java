package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.ExtensionContext;

import io.quarkus.launcher.RuntimeLaunchClassLoader;

public class NativeTestExtension implements NativeTestExtensionInterface {

    private static final String NATIVE_TEST_EXTENSION_INTERNAL = "io.quarkus.test.junit.NativeTestExtensionInternal";

    private final NativeTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public NativeTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (NativeTestExtensionInterface) contextCl
                    .loadClass(NATIVE_TEST_EXTENSION_INTERNAL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + NATIVE_TEST_EXTENSION_INTERNAL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + NATIVE_TEST_EXTENSION_INTERNAL, e);
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
