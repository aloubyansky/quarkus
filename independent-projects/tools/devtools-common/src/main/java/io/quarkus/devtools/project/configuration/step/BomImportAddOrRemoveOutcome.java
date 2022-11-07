package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.maven.dependency.ArtifactCoords;

class BomImportAddOrRemoveOutcome implements UpdateStepOutcome {

    protected final ArtifactCoords bom;
    protected final BomImportConflictKey conflictKey;

    public BomImportAddOrRemoveOutcome(ArtifactCoords bom, Path configFile) {
        this.bom = Objects.requireNonNull(bom, "BOM is null");
        this.conflictKey = new BomImportConflictKey(bom.getKey(), configFile);
    }

    public ArtifactCoords getBom() {
        return bom;
    }

    public Path getConfigurationFile() {
        return conflictKey.configurationFile();
    }

    @Override
    public ConflictKey getConflictKey() {
        return conflictKey;
    }
}
