package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.devtools.project.configuration.ConfiguredValue;

public class PomPropertyUpdateStep extends AbstractUpdateStep<PomPropertyUpdateStep.Id, PomPropertyUpdateStep.Key> {

    public static PomPropertyUpdateStep of(ConfiguredValue configuredValue, String newValue) {
        if (!configuredValue.isProperty()) {
            throw new IllegalArgumentException("Configured value " + configuredValue + " is not a property");
        }
        return new PomPropertyUpdateStep(configuredValue.getResolvedValue().getSource().getPath(),
                configuredValue.getPropertyName(), newValue);
    }

    public record Key(Path file, String propertyName) {
    };

    public record Id(Path file, String propertyName, String propertyValue) {
    };

    public PomPropertyUpdateStep(Path file, String propertyName, String propertyValue) {
        super(new Id(file, propertyName, propertyValue), new Key(file, propertyName), file);
    }

    @Override
    public String toString() {
        return "Property update: " + getId().propertyName + "=" + getId().propertyValue + ", file=" + getFile();
    }
}
