package io.quarkus.deployment.pkg.builditem;

import java.util.Set;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.pkg.PackageConfig.JarConfig.PurgeLevel;
import io.quarkus.maven.dependency.ArtifactKey;

/**
 * Build item that holds the results of dependency usage analysis for purge.
 * Contains the purge level, the set of reachable class names (dot-separated),
 * and the set of artifact keys for dependencies that have at least one reachable class.
 */
public final class UberJarPurgeBuildItem extends SimpleBuildItem {

    private final PurgeLevel level;
    private final Set<String> reachableClassNames;
    private final Set<ArtifactKey> usedDependencies;

    public UberJarPurgeBuildItem(PurgeLevel level, Set<String> reachableClassNames, Set<ArtifactKey> usedDependencies) {
        this.level = level;
        this.reachableClassNames = reachableClassNames;
        this.usedDependencies = usedDependencies;
    }

    public PurgeLevel getLevel() {
        return level;
    }

    /**
     * @return dot-separated class names of all reachable classes
     */
    public Set<String> getReachableClassNames() {
        return reachableClassNames;
    }

    /**
     * @return artifact keys of dependencies that have at least one reachable class
     */
    public Set<ArtifactKey> getUsedDependencies() {
        return usedDependencies;
    }
}
