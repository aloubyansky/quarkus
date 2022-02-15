package io.quarkus.gradle.tasks;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

public class QuarkusBootstrapTask extends QuarkusTask {

    private List<String> forcedDependencies = new ArrayList<>(0);

    public QuarkusBootstrapTask() {
        super("Quarkus bootstrap");
    }

    @Input
    public List<String> getForcedDependencies() {
        return forcedDependencies;
    }

    public void forcedDependencies(List<String> forcedDeps) {
        this.forcedDependencies.addAll(forcedDeps);
    }

    @TaskAction
    public void bootstrap() {
        System.out.println("bootstrap " + forcedDependencies);
    }
}
