package io.quarkus.gradle.tasks;

import java.util.List;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import io.quarkus.maven.dependency.GACTV;

public class QuarkusJibContainerImageTask extends QuarkusTaskWithForcedDependencies {

    public QuarkusJibContainerImageTask() {
        super("Quarkus task creating container images using Jib");
    }

    @Override
    @Input
    public List<GACTV> getForcedDependencies() {
        // 'managed' indicates the version must be found among the project's dependency constraints
        return List.of(GACTV.fromString("io.quarkus:quarkus-container-image-jib:managed"));
    }

    @TaskAction
    public void run() {
    }
}
