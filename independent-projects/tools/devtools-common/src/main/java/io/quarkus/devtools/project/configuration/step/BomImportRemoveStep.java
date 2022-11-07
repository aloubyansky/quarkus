package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportRemoveStep extends AbstractUpdateStep {

    private final BomImportRemoveOutcome outcome;

    public BomImportRemoveStep(ArtifactCoords bom, Path configFile) {
        this(new BomImportRemoveOutcome(bom, configFile));
    }

    private BomImportRemoveStep(BomImportRemoveOutcome outcome) {
        super(outcome);
        this.outcome = outcome;
    }

    @Override
    public String toString() {
        return "BOM remove: " + outcome.getBom().toCompactCoords() + ", file=" + outcome.getConfigurationFile();
    }
}
