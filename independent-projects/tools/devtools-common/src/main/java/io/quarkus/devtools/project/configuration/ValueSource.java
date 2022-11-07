package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

public interface ValueSource {

    static ValueSource external(WorkspaceModuleId module, Path p) {
        return new ExternalValueSource(module, p);
    }

    static ValueSource external(WorkspaceModuleId module, String property, Path p) {
        return new ExternalValueSource(module, property, p);
    }

    static ValueSource local(WorkspaceModuleId module, Path p) {
        return new LocalValueSource(module, p);
    }

    static ValueSource local(WorkspaceModuleId module, String property, Path p) {
        return new LocalValueSource(module, property, p);
    }

    boolean isExternal();

    default boolean isProperty() {
        return getProperty() != null;
    }

    String getProperty();

    Path getPath();

    WorkspaceModuleId getModule();
}
