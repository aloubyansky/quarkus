package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;

public class DependencyInstruction extends UpdateInstructionBase {

    public static DependencyInstruction add(Path configurationFile, ArtifactCoords coords) {
        return new DependencyInstruction(configurationFile, null, Objects.requireNonNull(coords));
    }

    public static DependencyInstruction remove(Path configurationFile, ArtifactCoords coords) {
        return new DependencyInstruction(configurationFile, Objects.requireNonNull(coords), null);
    }

    public static DependencyInstruction update(Path configurationFile, ArtifactCoords currentCoords, ArtifactCoords newCoords) {
        return new DependencyInstruction(configurationFile, Objects.requireNonNull(currentCoords),
                Objects.requireNonNull(newCoords));
    }

    private final DependencyKey key;
    private final ArtifactCoords currentCoords;
    private final ArtifactCoords newCoords;

    private DependencyInstruction(Path configurationFile, ArtifactCoords currentCoords, ArtifactCoords newCoords) {
        var keyCoords = currentCoords == null ? newCoords : currentCoords;
        if (keyCoords == null) {
            throw new IllegalArgumentException("Expected at least one of current or new BOMs to be not null");
        }
        this.key = new DependencyKey(Objects.requireNonNull(configurationFile),
                ArtifactKey.ga(keyCoords.getGroupId(), keyCoords.getArtifactId()));
        this.currentCoords = currentCoords;
        this.newCoords = newCoords;
    }

    @Override
    public DependencyKey getKey() {
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

    private boolean isSameArtifactKey(DependencyInstruction other) {
        return getKeyCoords().getKey().equals(other.getKeyCoords().getKey());
    }

    @Override
    protected UpdateInstructionCompatibility compareToActionWithSameKey(UpdateInstruction other) {
        if (other instanceof DependencyInstruction otherDepAction) {
            if (otherDepAction.isRemove()) {
                if (isRemove()) {
                    return UpdateInstructionCompatibility.COMPATIBLE;
                }
                return isSameArtifactKey(otherDepAction) ? UpdateInstructionCompatibility.CONFLICTS
                        : UpdateInstructionCompatibility.COMPATIBLE;
            }
            if (isRemove()) {
                return isSameArtifactKey(otherDepAction) ? UpdateInstructionCompatibility.CONFLICTS
                        : UpdateInstructionCompatibility.COMPATIBLE;
            }
            if (isSameArtifactKey(otherDepAction)) {
                if (!newCoords.equals(otherDepAction.newCoords)) {
                    return UpdateInstructionCompatibility.CONFLICTS;
                }
                if (otherDepAction.isAddNew()) {
                    return isAddNew() ? UpdateInstructionCompatibility.MATCHES : UpdateInstructionCompatibility.SUPERSEDES;
                }
                return isAddNew() ? UpdateInstructionCompatibility.SUPERSEDED : UpdateInstructionCompatibility.MATCHES;
            }
            return newCoords.getVersion().equals(otherDepAction.newCoords.getVersion())
                    ? UpdateInstructionCompatibility.COMPATIBLE
                    : UpdateInstructionCompatibility.CONFLICTS;
        } else {
            throw new IllegalArgumentException(
                    "Expected other to be an instance of " + getClass().getName() + " but got " + other.getClass().getName());
        }
    }

    @Override
    public String toString() {
        if (isAddNew()) {
            return "Add dependency: " + newCoords.toCompactCoords() + " to " + key.configurationFile();
        } else if (isRemove()) {
            return "Remove dependency: " + currentCoords.toCompactCoords() + " from " + key.configurationFile();
        } else {
            return "Update dependency: " + currentCoords.toCompactCoords() + " -> " + newCoords.toCompactCoords();
        }
    }
}
