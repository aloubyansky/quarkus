package io.quarkus.maven.dependency;

import io.quarkus.bootstrap.workspace.ProjectModule;
import io.quarkus.paths.PathCollection;
import java.io.Serializable;
import java.nio.file.Path;

public class ProjectArtifact extends DefaultArtifact implements Serializable {

    private final ProjectModule module;

    public ProjectArtifact(ProjectModule module, ArtifactCoords coords, Path... paths) {
        super(coords, paths);
        this.module = module;
    }

    public ProjectArtifact(ProjectModule origin, ArtifactCoords coords, PathCollection paths) {
        super(coords, paths);
        this.module = origin;
    }

    @Override
    public ProjectModule getModule() {
        return module;
    }
}
