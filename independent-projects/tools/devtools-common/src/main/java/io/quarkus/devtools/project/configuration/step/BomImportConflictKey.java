package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactKey;

record BomImportConflictKey(ArtifactKey bomKey, Path configurationFile) implements ConflictKey {
}
