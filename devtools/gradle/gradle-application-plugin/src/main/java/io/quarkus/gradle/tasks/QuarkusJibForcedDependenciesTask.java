package io.quarkus.gradle.tasks;

import java.util.List;

import org.gradle.api.tasks.TaskAction;

public class QuarkusJibForcedDependenciesTask extends QuarkusForcedDependenciesProvider {

    public QuarkusJibForcedDependenciesTask() {
        super("JIB forced dependencies");
    }

    @TaskAction
    public void forcedDeps() {
        System.out.println("JIB forced deps");
    }

    @Override
    public List<String> forcedDependencies() {
        return List.of("io.quarkus:quarkus-jib");
    }
}
