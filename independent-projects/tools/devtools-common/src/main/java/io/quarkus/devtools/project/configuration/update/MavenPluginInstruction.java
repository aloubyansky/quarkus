package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.maven.dependency.ArtifactCoords;

public class MavenPluginInstruction extends UpdateInstructionBase {

    public static MavenPluginInstruction add(Path configurationFile, ArtifactCoords coords) {
        return new MavenPluginInstruction(configurationFile, null, Objects.requireNonNull(coords));
    }

    public static MavenPluginInstruction remove(Path configurationFile, ArtifactCoords coords) {
        return new MavenPluginInstruction(configurationFile, Objects.requireNonNull(coords), null);
    }

    public static MavenPluginInstruction update(Path configurationFile, ArtifactCoords currentCoords,
            ArtifactCoords newCoords) {
        return new MavenPluginInstruction(configurationFile, Objects.requireNonNull(currentCoords),
                Objects.requireNonNull(newCoords));
    }

    private final MavenPluginKey key;
    private final ArtifactCoords currentCoords;
    private final ArtifactCoords newCoords;

    private MavenPluginInstruction(Path configurationFile, ArtifactCoords currentCoords, ArtifactCoords newCoords) {
        var keyCoords = currentCoords == null ? newCoords : currentCoords;
        if (keyCoords == null) {
            throw new IllegalArgumentException("Expected at least one of current or new BOMs to be not null");
        }
        this.key = new MavenPluginKey(Objects.requireNonNull(configurationFile), keyCoords.getKey());
        this.currentCoords = currentCoords;
        this.newCoords = newCoords;
    }

    @Override
    public MavenPluginKey getKey() {
        return key;
    }

    @Override
    public Path getFile() {
        return key.configurationFile();
    }

    public ArtifactCoords getCurrentCoords() {
        return currentCoords;
    }

    public ArtifactCoords getNewCoords() {
        return newCoords;
    }

    private boolean isRemove() {
        return newCoords == null;
    }

    private boolean isAddNew() {
        return currentCoords == null;
    }

    private ArtifactCoords getKeyCoords() {
        return currentCoords == null ? newCoords : currentCoords;
    }

    @Override
    protected UpdateInstructionCompatibility compareToActionWithSameKey(UpdateInstruction other) {
        if (other instanceof MavenPluginInstruction otherPluginAction) {
            if (otherPluginAction.isRemove()) {
                if (isRemove()) {
                    return UpdateInstructionCompatibility.COMPATIBLE;
                }
                return UpdateInstructionCompatibility.CONFLICTS;
            }
            if (isRemove()) {
                return UpdateInstructionCompatibility.CONFLICTS;
            }
            if (!newCoords.equals(otherPluginAction.newCoords)) {
                return UpdateInstructionCompatibility.CONFLICTS;
            }
            if (otherPluginAction.isAddNew()) {
                return isAddNew() ? UpdateInstructionCompatibility.MATCHES : UpdateInstructionCompatibility.SUPERSEDES;
            }
            return isAddNew() ? UpdateInstructionCompatibility.SUPERSEDED : UpdateInstructionCompatibility.MATCHES;
        } else {
            throw new IllegalArgumentException(
                    "Expected other to be an instance of " + getClass().getName() + " but got " + other.getClass().getName());
        }
    }

    @Override
    public String toString() {
        if (isAddNew()) {
            return "Add Maven plugin: " + newCoords.toCompactCoords() + " to " + key.configurationFile();
        } else if (isRemove()) {
            return "Remove Maven plugin: " + currentCoords.toCompactCoords() + " from " + key.configurationFile();
        } else {
            return "Update Maven plugin: " + currentCoords.toCompactCoords() + " -> " + newCoords.toCompactCoords();
        }
    }
}
