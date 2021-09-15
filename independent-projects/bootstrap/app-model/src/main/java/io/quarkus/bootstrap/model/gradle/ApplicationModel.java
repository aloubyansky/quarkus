package io.quarkus.bootstrap.model.gradle;

import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.workspace.ProjectModule;
import io.quarkus.bootstrap.workspace.ProjectModuleId;
import io.quarkus.maven.dependency.Artifact;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.Dependency;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ApplicationModel {

    Artifact getAppArtifact();

    List<Dependency> getDependencies();

    PlatformImports getPlatformImports();

    Collection<ExtensionCapabilities> getExtensionCapabilities();

    Set<ArtifactKey> getParentFirst();

    Set<ArtifactKey> getRunnerParentFirst();

    Set<ArtifactKey> getLowerPriorityArtifacts();

    Set<ArtifactKey> getLocalProjectDependencies();

    default ProjectModule getApplicationModule() {
        return getAppArtifact().getModule();
    }

    default Collection<ProjectModule> getProjectModules() {
        final Map<ProjectModuleId, ProjectModule> modules = new HashMap<>();
        for (Dependency d : getDependencies()) {
            if (!d.isProjectModule()) {
                continue;
            }
            final ProjectModule module = d.getArtifact().getModule();
            modules.put(module.getId(), module);
        }
        return modules.values();
    }
}
