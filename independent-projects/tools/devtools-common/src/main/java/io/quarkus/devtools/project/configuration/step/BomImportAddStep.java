package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactCoords;

public class BomImportAddStep extends AbstractUpdateStep {

    private final BomImportAddOutcome outcome;

    public BomImportAddStep(ArtifactCoords bom, Path configFile) {
        this(new BomImportAddOutcome(bom, configFile));
    }

    private BomImportAddStep(BomImportAddOutcome outcome) {
        super(outcome);
        this.outcome = outcome;
    }

    @Override
    public String toString() {
        return "BOM add: " + outcome.getBom().toCompactCoords() + ", file=" + outcome.getConfigurationFile();
    }
}
