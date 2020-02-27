package io.quarkus.gradle.tasks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.testing.Test;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.gradle.QuarkusPluginExtension;
import io.quarkus.runtime.LaunchMode;

public class QuarkusTestConfig extends QuarkusTask {

    public QuarkusTestConfig() {
        super("Sets the necessary system properties for the Quarkus tests to run.");
    }

    @TaskAction
    public void setupTest() {
        final QuarkusPluginExtension quarkusExt = extension();
        try {
            final AppModel appModel = quarkusExt.getAppModelResolver(LaunchMode.TEST)
                    .resolveModel(quarkusExt.getAppArtifact());

            // In Gradle classes and resources output are separate dirs.
            // Given that our augmentation assumes they are collocated,
            // that's what we are doing here
            final Path appRoot = appModel.getAppArtifact().getPath();
            if (Files.isDirectory(appRoot)) {
                final Path configDir = extension().outputConfigDirectory().toPath();
                if (Files.exists(configDir) && !appRoot.equals(configDir)) {
                    final Path rootDir = getTemporaryDir().toPath().resolve("quarkus-app-root");
                    if (Files.exists(rootDir)) {
                        IoUtils.recursiveDelete(rootDir);
                    }
                    Files.createDirectories(rootDir);
                    IoUtils.copy(appRoot, rootDir);
                    IoUtils.copy(configDir, rootDir);
                    appModel.getAppArtifact().setPath(rootDir);
                }
            }

            final String nativeRunner = getProject().getBuildDir().toPath().resolve(quarkusExt.finalName() + "-runner")
                    .toAbsolutePath()
                    .toString();

            final Path serializedModel = QuarkusGradleUtils.serializeAppModel(appModel, this);

            for (Test test : getProject().getTasks().withType(Test.class)) {
                final Map<String, Object> props = test.getSystemProperties();
                props.put("native.image.path", nativeRunner);
                props.put(BootstrapConstants.SERIALIZED_APP_MODEL, serializedModel.toString());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve deployment classpath", e);
        }
    }
}
