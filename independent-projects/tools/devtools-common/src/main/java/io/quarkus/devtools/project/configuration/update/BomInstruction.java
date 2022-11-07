package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomInstruction extends UpdateInstructionBase {

    public static BomInstruction add(Path configurationFile, ArtifactCoords newBom) {
        return new BomInstruction(configurationFile, null, Objects.requireNonNull(newBom));
    }

    public static BomInstruction remove(Path configurationFile, ArtifactCoords currentBom) {
        return new BomInstruction(configurationFile, Objects.requireNonNull(currentBom), null);
    }

    public static BomInstruction update(Path configurationFile, ArtifactCoords currentBom, ArtifactCoords newBom) {
        return new BomInstruction(configurationFile, Objects.requireNonNull(currentBom), Objects.requireNonNull(newBom));
    }

    private final BomKey key;
    private final ArtifactCoords currentBom;
    private final ArtifactCoords newBom;

    private BomInstruction(Path configurationFile, ArtifactCoords currentBom, ArtifactCoords newBom) {
        var keyBom = currentBom == null ? newBom : currentBom;
        if (keyBom == null) {
            throw new IllegalArgumentException("Expected at least one of current or new BOMs to be not null");
        }
        this.key = new BomKey(Objects.requireNonNull(configurationFile), keyBom.getKey());
        this.currentBom = currentBom;
        this.newBom = newBom;
    }

    @Override
    public BomKey getKey() {
        return key;
    }

    @Override
    public Path getFile() {
        return key.configurationFile();
    }

    public ArtifactCoords getCurrentCoords() {
        return currentBom;
    }

    public ArtifactCoords getNewCoords() {
        return newBom;
    }

    private boolean isRemove() {
        return newBom == null;
    }

    private boolean isAddNew() {
        return currentBom == null;
    }

    @Override
    protected UpdateInstructionCompatibility compareToActionWithSameKey(UpdateInstruction other) {
        if (other instanceof BomInstruction otherBomUpdate) {
            if (otherBomUpdate.isRemove()) {
                if (isRemove()) {
                    return UpdateInstructionCompatibility.COMPATIBLE;
                }
                return UpdateInstructionCompatibility.CONFLICTS;
            }
            if (isRemove()) {
                return UpdateInstructionCompatibility.CONFLICTS;
            }
            if (!newBom.equals(otherBomUpdate.newBom)) {
                return UpdateInstructionCompatibility.CONFLICTS;
            }
            if (otherBomUpdate.isAddNew()) {
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
            return "Add BOM: " + newBom.toCompactCoords() + " to " + key.configurationFile();
        }
        if (isRemove()) {
            return "Remove BOM: " + currentBom.toCompactCoords() + " from " + key.configurationFile();
        }
        return "Update BOM: " + currentBom.toCompactCoords() + " -> " + newBom.toCompactCoords() + " in "
                + key.configurationFile();
    }
}
