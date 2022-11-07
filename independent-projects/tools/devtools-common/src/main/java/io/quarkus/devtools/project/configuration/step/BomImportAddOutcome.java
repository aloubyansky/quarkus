package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportAddOutcome extends BomImportAddOrRemoveOutcome {

    public BomImportAddOutcome(ArtifactCoords bom, Path configFile) {
        super(bom, configFile);
    }

    @Override
    public boolean isCompatible(UpdateStepOutcome other) {
        if (!getConflictKey().equals(other.getConflictKey())) {
            return true;
        }
        if (getClass() == other.getClass()) {
            return bom.equals(((BomImportAddOutcome) other).getBom());
        }
        if (other.getClass() == BomImportRemoveOutcome.class) {
            return !bom.equals(((BomImportRemoveOutcome) other).getBom());
        }
        throw new RuntimeException("Unexpected outcome type " + other.getClass().getName());
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass())
            return false;

        BomImportAddOutcome that = (BomImportAddOutcome) o;
        return bom.equals(that.bom) && conflictKey.equals(that.conflictKey);
    }

    @Override
    public int hashCode() {
        int result = bom.hashCode();
        result = 31 * result + conflictKey.hashCode();
        return result;
    }
}
