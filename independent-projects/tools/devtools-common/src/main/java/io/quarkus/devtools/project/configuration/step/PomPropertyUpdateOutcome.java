package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;
import java.util.Objects;

public class PomPropertyUpdateOutcome implements UpdateStepOutcome {

    private final String propertyValue;
    private final PomPropertyUpdateConflictKey conflictKey;

    public PomPropertyUpdateOutcome(String propertyName, String propertyValue, Path configFile) {
        this.propertyValue = Objects.requireNonNull(propertyValue, "Property value is null");
        this.conflictKey = new PomPropertyUpdateConflictKey(propertyName, configFile);
    }

    public String getPropertyName() {
        return conflictKey.propertyName();
    }

    public String getPropertyValue() {
        return propertyValue;
    }

    public Path getConfigurationFile() {
        return conflictKey.configurationFile();
    }

    @Override
    public ConflictKey getConflictKey() {
        return conflictKey;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass())
            return false;
        PomPropertyUpdateOutcome that = (PomPropertyUpdateOutcome) o;
        return Objects.equals(propertyValue, that.propertyValue) && Objects.equals(conflictKey, that.conflictKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyValue, conflictKey);
    }
}
