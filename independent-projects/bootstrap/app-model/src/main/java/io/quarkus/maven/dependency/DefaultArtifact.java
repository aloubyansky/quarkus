package io.quarkus.maven.dependency;

import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathList;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Objects;

public class DefaultArtifact implements Artifact, Serializable {

    private final ArtifactCoords coords;
    private final PathCollection paths;

    public DefaultArtifact(ArtifactCoords coords, Path... paths) {
        this(coords, paths.length == 0 ? null : PathList.of(paths));
    }

    public DefaultArtifact(ArtifactCoords coords, PathCollection paths) {
        this.coords = coords;
        this.paths = paths;
    }

    @Override
    public ArtifactCoords getCoords() {
        return coords;
    }

    @Override
    public PathCollection getResolvedPaths() {
        return paths;
    }

    @Override
    public int hashCode() {
        return Objects.hash(coords, paths);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DefaultArtifact other = (DefaultArtifact) obj;
        return Objects.equals(coords, other.coords) && Objects.equals(paths, other.paths);
    }

    @Override
    public String toString() {
        return coords + " " + paths;
    }
}
