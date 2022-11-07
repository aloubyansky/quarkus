package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;
import java.util.List;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportUpdateStep extends AbstractUpdateStep {

    private final ArtifactCoords currentRawBom;
    private final ArtifactCoords newRawBom;
    private final Path configFile;

    public BomImportUpdateStep(ArtifactCoords currentRawBom, ArtifactCoords newRawBom, Path configFile) {
        super(List.of(
                new BomImportRemoveOutcome(currentRawBom, configFile),
                new BomImportAddOutcome(newRawBom, configFile)));
        this.currentRawBom = currentRawBom;
        this.newRawBom = newRawBom;
        this.configFile = configFile;
    }

    @Override
    public String toString() {
        return "BOM update: " + currentRawBom.toCompactCoords() + " -> " + newRawBom + ", file=" + configFile;
    }
}
