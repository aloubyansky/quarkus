package io.quarkus.devtools.project.configuration.step;

import java.nio.file.Path;

record PomPropertyUpdateConflictKey(String propertyName, Path configurationFile) implements ConflictKey {
}
