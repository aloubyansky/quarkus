package io.quarkus.deployment.pkg.builditem;

import java.util.List;

import io.quarkus.maven.dependency.ArtifactCoords;

/**
 * Tree-shake analysis result for a single runtime dependency.
 * <p>
 * Details that would cost every build to collect, such as the names of the kept classes, are not part of it
 * and can be computed with {@link JarTreeShakeBuildItem#collectReachableClasses}.
 *
 * @param coords coordinates of the dependency
 * @param excludedFromTreeShaking whether the dependency is excluded from tree shaking, in which case none of its
 *        classes are removed
 * @param resourcesOnly whether the dependency contains no classes; the analysis can't tell whether such a dependency
 *        is used, since its resources may be read at runtime
 * @param originalClassCount number of classes attributed to the dependency before tree shaking; a class present in
 *        more than one dependency is attributed to the dependency that comes first on the classpath
 * @param removedClasses sorted class resource paths (e.g. {@code com/example/Foo.class}) of the classes removed by
 *        tree shaking
 */
public record JarTreeShakeDependencyInfo(
        ArtifactCoords coords,
        boolean excludedFromTreeShaking,
        boolean resourcesOnly,
        int originalClassCount,
        List<String> removedClasses) {

    /**
     * @return number of attributed classes kept by tree shaking
     */
    public int keptClassCount() {
        return originalClassCount - removedClasses.size();
    }

    /**
     * @return whether the dependency had classes attributed to it and tree shaking removed all of them
     */
    public boolean allClassesRemoved() {
        return originalClassCount > 0 && removedClasses.size() == originalClassCount;
    }
}
