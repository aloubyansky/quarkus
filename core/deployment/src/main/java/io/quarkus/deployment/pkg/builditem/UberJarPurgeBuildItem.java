package io.quarkus.deployment.pkg.builditem;

import java.util.Set;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.pkg.PackageConfig.JarConfig.PurgeLevel;
import io.quarkus.maven.dependency.ArtifactKey;

/**
 * Build item that holds the results of dependency usage analysis for purge.
 * Contains the purge level and the set of artifact keys for dependencies
 * that have at least one reachable class.
 * <p>
 * Class-level removal is handled separately via {@code RemovedResourceBuildItem}.
 */
public final class UberJarPurgeBuildItem extends SimpleBuildItem {

    private final PurgeLevel level;
    private final Set<ArtifactKey> usedDependencies;

    public UberJarPurgeBuildItem(PurgeLevel level, Set<ArtifactKey> usedDependencies) {
        this.level = level;
        this.usedDependencies = usedDependencies;
    }

    public PurgeLevel getLevel() {
        return level;
    }

    /**
     * @return artifact keys of dependencies that have at least one reachable class
     */
    public Set<ArtifactKey> getUsedDependencies() {
        return usedDependencies;
    }
}
