package io.quarkus.deployment.pkg.builditem;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.commons.classloading.ClassLoaderHelper;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * Build item that holds the results of dependency usage analysis for tree shaking.
 * Contains whether class-level tree shaking was performed, the set of reachable class names (dot-separated)
 * and the analysis result for each runtime dependency.
 */
public final class JarTreeShakeBuildItem extends SimpleBuildItem {

    private final boolean classesShaken;
    private final Set<String> reachableClassNames;
    private final Map<ArtifactKey, JarTreeShakeDependencyInfo> dependencies;
    private final Set<String> referencedJdkPackages;

    public JarTreeShakeBuildItem(boolean classesShaken, Set<String> reachableClassNames,
            Map<ArtifactKey, JarTreeShakeDependencyInfo> dependencies, Set<String> referencedJdkPackages) {
        this.classesShaken = classesShaken;
        this.reachableClassNames = reachableClassNames;
        this.dependencies = dependencies;
        this.referencedJdkPackages = referencedJdkPackages;
    }

    public boolean isClassesShaken() {
        return classesShaken;
    }

    /**
     * @return dot-separated class names of all reachable classes
     */
    public Set<String> getReachableClassNames() {
        return reachableClassNames;
    }

    /**
     * @return analysis result for each runtime dependency, empty if classes were not shaken
     */
    public Map<ArtifactKey, JarTreeShakeDependencyInfo> getDependencies() {
        return dependencies;
    }

    /**
     * Collects JAR entry paths of unreachable classes from a dependency into the given set.
     * Handles multi-release JAR entries. Nested classes are removed independently of their enclosing class:
     * the analysis already keeps the enclosing class of every reachable nested class, and the member classes
     * of a class whose {@link Class#getDeclaredClasses()} is called.
     *
     * @param dep the dependency to scan
     * @param removedEntries the set to add removed entry paths to
     */
    public void collectUnreachableEntries(ResolvedDependency dep, Set<String> removedEntries) throws IOException {
        if (!classesShaken) {
            return;
        }
        walkClassEntries(dep, (entry, className) -> {
            if (!reachableClassNames.contains(className)) {
                removedEntries.add(entry);
            }
        });
    }

    /**
     * Collects the dot-separated names of the reachable classes found in a dependency into the given set.
     * Unlike {@link JarTreeShakeDependencyInfo#keptClassCount()}, it includes the reachable classes that are also
     * present in a dependency that comes earlier on the classpath.
     *
     * @param dep the dependency to scan
     * @param reachableClasses the set to add reachable class names to
     */
    public void collectReachableClasses(ResolvedDependency dep, Set<String> reachableClasses) throws IOException {
        if (!classesShaken) {
            return;
        }
        walkClassEntries(dep, (entry, className) -> {
            if (reachableClassNames.contains(className)) {
                reachableClasses.add(className);
            }
        });
    }

    /**
     * Passes the JAR entry path and the dot-separated class name of each class entry of a dependency,
     * including multi-release entries, to the given consumer.
     */
    private static void walkClassEntries(ResolvedDependency dep, BiConsumer<String, String> consumer)
            throws IOException {
        try (var pathTree = dep.getContentTree().open()) {
            pathTree.walkRaw(visit -> {
                String rel = visit.getRelativePath("/");
                String classRel = rel;
                if (rel.startsWith("META-INF/versions/")) {
                    String afterVersions = rel.substring("META-INF/versions/".length());
                    int slash = afterVersions.indexOf('/');
                    if (slash > 0) {
                        classRel = afterVersions.substring(slash + 1);
                    } else {
                        return;
                    }
                }
                if (ClassLoaderHelper.isClassEntry(classRel)) {
                    consumer.accept(rel, classRel.substring(0, classRel.length() - 6).replace('/', '.'));
                }
            });
        }
    }

    /**
     * @return dot-separated names of packages possibly provided by the JDK that are referenced by reachable code
     */
    public Set<String> getReferencedJdkPackages() {
        return referencedJdkPackages;
    }

    /**
     * Computes a pedigree string for the given dependency describing what was removed.
     *
     * @return pedigree text or {@code null} if nothing was removed
     */
    public String computePedigree(ArtifactKey depKey) {
        if (!classesShaken) {
            return null;
        }
        final JarTreeShakeDependencyInfo dep = dependencies.get(depKey);
        if (dep == null || dep.removedClasses().isEmpty()) {
            return null;
        }
        final List<String> removed = dep.removedClasses();
        var sb = new StringBuilder("Removed ");
        sb.append(removed.get(0));
        for (int i = 1; i < removed.size(); ++i) {
            sb.append(",").append(removed.get(i));
        }
        return sb.toString();
    }
}
