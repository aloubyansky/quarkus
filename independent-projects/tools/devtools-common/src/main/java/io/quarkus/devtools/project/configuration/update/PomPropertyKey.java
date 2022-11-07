package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;

public record PomPropertyKey(Path configurationFile, String propertyName) implements UpdateInstructionKey {
}
