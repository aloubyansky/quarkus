package io.quarkus.gradle.tasks;

import java.util.List;

import org.gradle.api.tasks.TaskAction;

public class QuarkusDockerForcedDependenciesTask extends QuarkusForcedDependenciesProvider {

    public QuarkusDockerForcedDependenciesTask() {
        super("Docker forced dependencies");
    }

    @TaskAction
    public void forcedDeps() {
        System.out.println("Docker forced deps");
    }

    @Override
    public List<String> forcedDependencies() {
        return List.of("io.quarkus:quarkus-docker");
    }
}
