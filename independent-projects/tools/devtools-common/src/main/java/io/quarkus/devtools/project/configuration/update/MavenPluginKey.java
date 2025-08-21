package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;

import io.quarkus.maven.dependency.ArtifactKey;

public record MavenPluginKey(Path configurationFile, ArtifactKey artifactKey) implements UpdateInstructionKey {
}
