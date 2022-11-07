package io.quarkus.devtools.project.configuration.step;

public interface UpdateStepOutcome {

    static boolean matchOrDifferentConflictKeys(UpdateStepOutcome one, UpdateStepOutcome two) {
        return !one.getConflictKey().equals(two.getConflictKey()) || one.matches(two);
    }

    ConflictKey getConflictKey();

    default boolean isCompatible(UpdateStepOutcome other) {
        return matchOrDifferentConflictKeys(this, other);
    }

    default boolean matches(UpdateStepOutcome other) {
        return equals(other);
    }
}
