package io.quarkus.devtools.project.configuration.step;

import java.util.Collection;

public interface UpdateStep {

    Collection<UpdateStepOutcome> getOutcomes();

    default boolean isCompatible(UpdateStep other) {
        var thisOutcomes = getOutcomes();
        var otherOutcomes = other.getOutcomes();
        if (thisOutcomes.size() > otherOutcomes.size()) {
            var tmp = thisOutcomes;
            thisOutcomes = otherOutcomes;
            otherOutcomes = tmp;
        }
        for (var thisOutcome : getOutcomes()) {
            for (var otherOutcome : other.getOutcomes()) {
                if (!thisOutcome.isCompatible(otherOutcome)) {
                    return false;
                }
            }
        }
        return true;
    }
}
