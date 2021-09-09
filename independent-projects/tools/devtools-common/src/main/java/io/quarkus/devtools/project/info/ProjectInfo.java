package io.quarkus.devtools.project.info;

import io.quarkus.maven.ArtifactCoords;
import java.util.Collection;

public interface ProjectInfo {

    /**
     * Collection of imported platform BOMs in the order they are imported.
     *
     * @return imported platform BOMs
     */
    Collection<ArtifactCoords> getImportedPlatformBoms();

    /**
     * Collection of direct extension dependencies that are managed by the imported platform BOMs.
     *
     * @return collection of direct platform extension dependencies
     */
    Collection<ArtifactCoords> getPlatformExtensions();

    /**
     * Collection of direct extension dependencies that are not managed by the imported platform BOMs.
     *
     * @return collection of direct non-platform extension dependencies
     */
    Collection<ArtifactCoords> getNonPlatformExtensions();

}
