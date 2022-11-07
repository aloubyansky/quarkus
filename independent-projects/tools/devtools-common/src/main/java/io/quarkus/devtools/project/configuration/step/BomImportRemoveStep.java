package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportRemoveStep extends AbstractUpdateStep<BomImportRemoveStep.Id, BomImportRemoveStep.Id> {

    public record Id(Path file, ArtifactCoords coords) {
    };

    public BomImportRemoveStep(Path file, ArtifactCoords coords) {
        this(new Id(file, coords));
    }

    private BomImportRemoveStep(Id id) {
        super(id, id, id.file);
    }

    @Override
    public String toString() {
        return "BOM remove: " + getId().coords + ", file=" + getFile();
    }
}
