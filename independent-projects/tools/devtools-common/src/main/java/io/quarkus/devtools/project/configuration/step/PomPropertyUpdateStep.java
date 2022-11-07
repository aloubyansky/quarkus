package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;

public class PomPropertyUpdateStep extends AbstractUpdateStep {

    public static PomPropertyUpdateStep of(ConfiguredValue configuredValue, String newValue) {
        return of(configuredValue.getResolvedValue(), newValue);
    }

    public static PomPropertyUpdateStep of(ResolvedValue configuredValue, String newValue) {
        if (!configuredValue.getSource().isProperty()) {
            throw new IllegalArgumentException("Configured value " + configuredValue + " is not a property");
        }
        return new PomPropertyUpdateStep(configuredValue.getSource().getProperty(), newValue,
                configuredValue.getSource().getPath());
    }

    private PomPropertyUpdateOutcome outcome;

    public PomPropertyUpdateStep(String propertyName, String propertyValue, Path configFile) {
        this(new PomPropertyUpdateOutcome(propertyName, propertyValue, configFile));
    }

    public PomPropertyUpdateStep(PomPropertyUpdateOutcome outcome) {
        super(outcome);
        this.outcome = outcome;
    }

    @Override
    public String toString() {
        return "Property update: " + outcome.getPropertyName() + "=" + outcome.getPropertyValue() + ", file="
                + outcome.getConfigurationFile();
    }
}
