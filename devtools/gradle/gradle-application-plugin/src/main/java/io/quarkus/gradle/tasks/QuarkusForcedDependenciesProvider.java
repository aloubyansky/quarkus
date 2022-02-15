package io.quarkus.gradle.tasks;

import java.util.List;
import java.util.function.Consumer;

public abstract class QuarkusForcedDependenciesProvider extends QuarkusTask implements Consumer<QuarkusBootstrapTask> {

    QuarkusForcedDependenciesProvider(String description) {
        super(description);
    }

    public abstract List<String> forcedDependencies();

    @Override
    public void accept(QuarkusBootstrapTask t) {
        t.forcedDependencies(forcedDependencies());
    }
}
