package io.quarkus.bootstrap.resolver.maven.workspace;

import io.quarkus.bootstrap.workspace.ProjectModule;
import io.quarkus.maven.dependency.ArtifactKey;

public interface ProjectModuleResolver {

    ProjectModule getProjectModule(String groupId, String artifactId);

    default ProjectModule getProjectModule(ArtifactKey key) {
        return getProjectModule(key.getGroupId(), key.getArtifactId());
    }
}
