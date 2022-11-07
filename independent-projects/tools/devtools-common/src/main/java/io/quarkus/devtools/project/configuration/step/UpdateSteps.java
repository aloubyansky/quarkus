package io.quarkus.devtools.project.configuration.step;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.devtools.commands.handlers.RegistryProjectInfo;
import io.quarkus.devtools.project.configuration.ConfiguredModule;

public class UpdateSteps {

    /**
     * Provides a list of steps that would align the current project with the registry recommendations.
     *
     * @param module current project
     * @param registryProjectInfo registry recommendation
     * @return update steps, never null
     */
    public static List<UpdateStep<?, ?>> getUpdateSteps(ConfiguredModule module, RegistryProjectInfo registryProjectInfo) {

        var allUpdateSteps = new UpdateSteps();
        new BomMapper(module, registryProjectInfo).addSteps(allUpdateSteps);

        return allUpdateSteps.asList();
    }

    private List<UpdateStep<?, ?>> updateList = new ArrayList<>();

    private UpdateSteps() {
    }

    void addStep(UpdateStep<?, ?> step) {
        // TODO:
        // - deduplication
        // - conflict checking
        updateList.add(step);
    }

    List<UpdateStep<?, ?>> asList() {
        return updateList;
    }
}
