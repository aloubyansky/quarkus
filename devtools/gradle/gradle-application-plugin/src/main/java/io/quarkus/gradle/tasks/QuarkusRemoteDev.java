package io.quarkus.gradle.tasks;

import io.quarkus.deployment.dev.DevModeCommandLineBuilder;

public abstract class QuarkusRemoteDev extends QuarkusDev {

    protected void modifyDevModeContext(DevModeCommandLineBuilder builder) {
        builder.remoteDev(true);
    }
}
