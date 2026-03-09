package io.quarkus.deployment.pkg.builditem;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.pkg.PackageConfig.JarConfig.PurgeLevel;
import io.quarkus.maven.dependency.ArtifactKey;

/**
 * Build item that holds the results of dependency usage analysis for purge.
 * Contains the purge level, the set of reachable class names (dot-separated),
 * the set of artifact keys for dependencies that have at least one reachable class,
 * and the sorted list of removed class resource paths per dependency.
 */
public final class UberJarPurgeBuildItem extends SimpleBuildItem {

    private final PurgeLevel level;
    private final Set<String> reachableClassNames;
    private final Set<ArtifactKey> usedDependencies;
    private final Map<ArtifactKey, List<String>> removedClasses;

    public UberJarPurgeBuildItem(PurgeLevel level, Set<String> reachableClassNames, Set<ArtifactKey> usedDependencies,
            Map<ArtifactKey, List<String>> removedClasses) {
        this.level = level;
        this.reachableClassNames = reachableClassNames;
        this.usedDependencies = usedDependencies;
        this.removedClasses = removedClasses;
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

    /**
     * @return sorted list of removed class resource paths (e.g. "com/example/Foo.class") per dependency
     */
    public Map<ArtifactKey, List<String>> getRemovedClasses() {
        return removedClasses;
    }

    /**
     * Computes a pedigree string for the given dependency describing what was removed.
     *
     * @return pedigree text or {@code null} if nothing was removed
     */
    public String computePedigree(ArtifactKey depKey) {
        if (level == PurgeLevel.NONE) {
            return null;
        }
        List<String> removed = removedClasses.getOrDefault(depKey, Collections.emptyList());
        if (removed.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder("Removed ");
        sb.append(removed.get(0));
        for (int i = 1; i < removed.size(); ++i) {
            sb.append(",").append(removed.get(i));
        }
        return sb.toString();
    }
}
