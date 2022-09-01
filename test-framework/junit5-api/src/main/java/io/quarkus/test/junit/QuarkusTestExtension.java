package io.quarkus.test.junit;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

import io.quarkus.launcher.RuntimeLaunchClassLoader;

public class QuarkusTestExtension implements QuarkusTestExtensionInterface {

    private static final String QUARKUS_TEST_EXTENSION_INTERNAL = "io.quarkus.test.junit.QuarkusTestExtensionInternal";

    private final QuarkusTestExtensionInterface delegate;
    private final ClassLoader contextCl;

    public QuarkusTestExtension() {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        contextCl = new RuntimeLaunchClassLoader(originalCl);
        Thread.currentThread().setContextClassLoader(contextCl);
        try {
            delegate = (QuarkusTestExtensionInterface) contextCl.loadClass(QUARKUS_TEST_EXTENSION_INTERNAL)
                    .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load class " + QUARKUS_TEST_EXTENSION_INTERNAL, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + QUARKUS_TEST_EXTENSION_INTERNAL, e);
        }
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Thread.currentThread().setContextClassLoader(contextCl);
        return delegate.evaluateExecutionCondition(context);
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
    public void afterAll(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.afterAll(context);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.beforeAll(context);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.afterEach(context);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.afterTestExecution(context);
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.beforeTestExecution(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Thread.currentThread().setContextClassLoader(contextCl);
        delegate.beforeEach(context);
    }
}
