package io.quarkus.deployment.pkg.builditem;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.pkg.PackageConfig.JarConfig.TreeShakeLevel;
import io.quarkus.maven.dependency.ArtifactKey;

/**
 * Build item that holds the results of dependency usage analysis for tree shaking.
 * Contains the tree shake level, the set of reachable class names (dot-separated),
 * and the sorted list of removed class resource paths per dependency.
 */
public final class JarTreeShakeBuildItem extends SimpleBuildItem {

    private final TreeShakeLevel level;
    private final Set<String> reachableClassNames;
    private final Map<ArtifactKey, List<String>> removedClasses;

    public JarTreeShakeBuildItem(TreeShakeLevel level, Set<String> reachableClassNames,
            Map<ArtifactKey, List<String>> removedClasses) {
        this.level = level;
        this.reachableClassNames = reachableClassNames;
        this.removedClasses = removedClasses;
    }

    public TreeShakeLevel getLevel() {
        return level;
    }

    /**
     * @return dot-separated class names of all reachable classes
     */
    public Set<String> getReachableClassNames() {
        return reachableClassNames;
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
        if (level == TreeShakeLevel.NONE) {
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
