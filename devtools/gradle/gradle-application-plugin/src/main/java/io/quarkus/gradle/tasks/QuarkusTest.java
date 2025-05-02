package io.quarkus.gradle.tasks;

import java.util.function.Consumer;

import io.quarkus.deployment.dev.DevModeCommandLineBuilder;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.IsolatedTestModeMain;

public abstract class QuarkusTest extends QuarkusDev {

    protected void modifyDevModeContext(DevModeCommandLineBuilder builder) {
        builder.entryPointCustomizer(new Consumer<DevModeContext>() {
            @Override
            public void accept(DevModeContext devModeContext) {
                devModeContext.setAlternateEntryPoint(IsolatedTestModeMain.class.getName());
            }
        });
    }
}
