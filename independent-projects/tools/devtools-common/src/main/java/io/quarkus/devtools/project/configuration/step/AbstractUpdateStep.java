package io.quarkus.devtools.project.configuration.step;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public abstract class AbstractUpdateStep implements UpdateStep {

    private final Collection<UpdateStepOutcome> outcomes;

    public AbstractUpdateStep(UpdateStepOutcome outcome) {
        this(List.of(outcome));
    }

    public AbstractUpdateStep(Collection<UpdateStepOutcome> outcomes) {
        this.outcomes = Objects.requireNonNull(outcomes);
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("No outcome provided");
        }
    }

    @Override
    public Collection<UpdateStepOutcome> getOutcomes() {
        return outcomes;
    }
}
