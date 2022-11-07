package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportUpdateStep extends AbstractUpdateStep<BomImportUpdateStep.Id, BomImportUpdateStep.Key> {

    public record Key(Path file, ArtifactCoords currentRawCoords) {
    };

    public record Id(Path file, ArtifactCoords currentRawCoords, ArtifactCoords newRawCoords) {
    };

    public BomImportUpdateStep(Path file, ArtifactCoords currentRawCoords, ArtifactCoords newRawCoords) {
        super(new Id(file, currentRawCoords, newRawCoords), new Key(file, currentRawCoords), file);
    }

    @Override
    public String toString() {
        return "BOM update: " + getId().currentRawCoords + " -> " + getId().newRawCoords + ", file=" + getFile();
    }
}
