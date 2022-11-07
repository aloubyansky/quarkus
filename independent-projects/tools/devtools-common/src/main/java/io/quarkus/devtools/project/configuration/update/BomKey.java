package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactKey;

public record BomKey(Path configurationFile, ArtifactKey bomKey) implements UpdateInstructionKey {
}
