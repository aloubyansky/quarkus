package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactKey;

public record DependencyKey(Path configurationFile, ArtifactKey artifactKey) implements UpdateInstructionKey {
}
