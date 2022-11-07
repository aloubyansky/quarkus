package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

class LocalValueSource implements ValueSource {

    private final WorkspaceModuleId module;
    private final String propertyName;
    private final Path p;

    LocalValueSource(WorkspaceModuleId module, Path p) {
        this.module = module;
        this.propertyName = null;
        this.p = p;
    }

    LocalValueSource(WorkspaceModuleId module, String propertyName, Path p) {
        this.module = module;
        this.propertyName = propertyName;
        this.p = p;
    }

    @Override
    public WorkspaceModuleId getModule() {
        return module;
    }

    @Override
    public boolean isExternal() {
        return false;
    }

    @Override
    public Path getPath() {
        return p;
    }

    @Override
    public String getProperty() {
        return propertyName;
    }

    @Override
    public int hashCode() {
        return Objects.hash(module, p, propertyName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LocalValueSource other = (LocalValueSource) obj;
        return Objects.equals(module, other.module) && Objects.equals(p, other.p)
                && Objects.equals(propertyName, other.propertyName);
    }

    @Override
    public String toString() {
        if (propertyName == null) {
            return p.toString();
        }
        return "${" + propertyName + "}@" + p.toString();
    }
}
