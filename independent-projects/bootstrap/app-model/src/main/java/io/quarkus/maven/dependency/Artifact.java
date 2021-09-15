package io.quarkus.maven.dependency;

import io.quarkus.bootstrap.workspace.ProjectModule;
import io.quarkus.paths.PathCollection;

public interface Artifact {

    ArtifactCoords getCoords();

    default ArtifactKey getKey() {
        return getCoords().getKey();
    }

    PathCollection getResolvedPaths();

    default boolean isResolved() {
        final PathCollection paths = getResolvedPaths();
        return paths != null && !paths.isEmpty();
    }

    default ProjectModule getModule() {
        return null;
    }
}
