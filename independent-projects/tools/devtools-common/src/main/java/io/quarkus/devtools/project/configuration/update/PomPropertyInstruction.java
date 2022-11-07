package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;

public class PomPropertyInstruction extends UpdateInstructionBase {

    public static PomPropertyInstruction update(ConfiguredValue configuredValue, String newValue) {
        return update(configuredValue.getResolvedValue(), newValue);
    }

    public static PomPropertyInstruction update(ResolvedValue configuredValue, String newValue) {
        if (!configuredValue.getSource().isProperty()) {
            throw new IllegalArgumentException("Configured value " + configuredValue + " is not a property");
        }
        return new PomPropertyInstruction(configuredValue.getSource().getPath(), configuredValue.getSource().getProperty(),
                newValue);
    }

    private final PomPropertyKey key;
    private final String propertyValue;

    public PomPropertyInstruction(Path configurationFile, String propertyName, String propertyValue) {
        this.key = new PomPropertyKey(Objects.requireNonNull(configurationFile), Objects.requireNonNull(propertyName));
        this.propertyValue = Objects.requireNonNull(propertyValue);
    }

    @Override
    public PomPropertyKey getKey() {
        return key;
    }

    @Override
    public Path getFile() {
        return key.configurationFile();
    }

    public String getPropertyName() {
        return key.propertyName();
    }

    public String getPropertyValue() {
        return propertyValue;
    }

    @Override
    protected UpdateInstructionCompatibility compareToActionWithSameKey(UpdateInstruction other) {
        if (other instanceof PomPropertyInstruction otherPropUpdate) {
            return otherPropUpdate.getPropertyValue().equals(getPropertyValue()) ? UpdateInstructionCompatibility.MATCHES
                    : UpdateInstructionCompatibility.CONFLICTS;
        } else {
            throw new IllegalArgumentException(
                    "Expected other to be an instance of " + getClass().getName() + " but got " + other.getClass().getName());
        }
    }

    @Override
    public String toString() {
        return "Update property: " + getPropertyName() + "=" + getPropertyValue() + ", file=" + getFile();
    }
}
