package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportRemoveOutcome extends BomImportAddOrRemoveOutcome {

    public BomImportRemoveOutcome(ArtifactCoords bom, Path configFile) {
        super(bom, configFile);
    }

    @Override
    public boolean isCompatible(UpdateStepOutcome other) {
        if (!getConflictKey().equals(other.getConflictKey())) {
            return true;
        }
        if (getClass() == other.getClass()) {
            return true;
        }
        if (other.getClass() == BomImportAddOutcome.class) {
            return !bom.equals(((BomImportAddOutcome) other).getBom());
        }
        throw new RuntimeException("Unexpected outcome type " + other.getClass().getName());
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass())
            return false;

        BomImportRemoveOutcome that = (BomImportRemoveOutcome) o;
        return conflictKey.equals(that.conflictKey);
    }

    @Override
    public int hashCode() {
        return conflictKey.hashCode();
    }
}
