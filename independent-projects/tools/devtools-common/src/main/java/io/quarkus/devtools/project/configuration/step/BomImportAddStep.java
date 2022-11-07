package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportAddStep extends AbstractUpdateStep<BomImportAddStep.Id, BomImportAddStep.Id> {

    public record Id(Path file, ArtifactCoords coords) {
    };

    public BomImportAddStep(Path file, ArtifactCoords coords) {
        this(new Id(file, coords));
    }

    private BomImportAddStep(Id id) {
        super(id, id, id.file);
    }

    @Override
    public String toString() {
        return "BOM add: " + getId().coords + ", file=" + getFile();
    }
}
