package io.quarkus.devtools.project.configuration.maven;

import java.nio.file.Path;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;
import io.quarkus.devtools.project.configuration.ValueSource;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;

abstract class AbstractModuleContainer implements ModuleContainer {

    protected final WorkspaceModuleId id;
    protected Model effectiveModel;
    private Map<ArtifactKey, Dependency> allEffectiveManagedDeps;

    public AbstractModuleContainer(WorkspaceModuleId id) {
        this.id = id;
    }

    public WorkspaceModuleId getId() {
        return id;
    }

    protected Map<ArtifactKey, Dependency> getAllEffectiveManagedDeps() {
        if (allEffectiveManagedDeps == null) {
            allEffectiveManagedDeps = ModuleContainer.getManagedDependencies(getEffectiveModel());
        }
        return allEffectiveManagedDeps;
    }

    protected Model getEffectiveModel() {
        return effectiveModel == null ? effectiveModel = resolveEffectiveModel() : effectiveModel;
    }

    protected abstract Model resolveEffectiveModel();

    @Override
    public boolean isPlatformBomEnforced(ArtifactCoords platformBom) {
        return getAllEffectiveManagedDeps().containsKey(ModuleContainer.getPlatformDescriptorKey(platformBom));
    }

    @Override
    public ResolvedValue resolvePropertyValue(String expr) {
        var name = ConfiguredValue.getPropertyName(expr);
        if (name.startsWith("project.")) {
            final String projectProp = name.substring("project.".length());
            switch (projectProp) {
                case "version":
                    return ResolvedValue.of(id.getVersion(), ValueSource.local(id, name, getPomFile()));
                case "groupId":
                    return ResolvedValue.of(id.getGroupId(), ValueSource.local(id, name, getPomFile()));
                case "artifactId":
                    return ResolvedValue.of(id.getArtifactId(), ValueSource.local(id, name, getPomFile()));
            }
        }
        return doResolvePropertyValue(name, expr);
    }

    protected abstract ResolvedValue doResolvePropertyValue(String propertyName, String expr);

    protected abstract Path getPomFile();
}
