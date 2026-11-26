package io.quarkus.deployment.pkg.steps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeDependencyInfo;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * Renders the dependency trim report from the tree-shake analysis result: the runtime dependencies that look unused
 * or barely used, as candidates for removal. The report lines are an internal format, not a stable API.
 */
class DependencyTrimReport {

    static final int DEFAULT_BARELY_USED_MAX_CLASSES = 10;
    static final int DEFAULT_MAX_LISTED_CLASSES = 20;

    private static final String INDENT = "  ";
    private static final String ITEM = INDENT + "- ";
    private static final String DETAIL = INDENT + INDENT + INDENT;
    private static final Comparator<JarTreeShakeDependencyInfo> BY_COORDS = Comparator
            .comparing(d -> d.coords().toCompactCoords());

    private final JarTreeShakeBuildItem treeShake;
    private final ApplicationModel appModel;
    private final int barelyUsedMaxClasses;
    private final int maxListedClasses;
    private final Map<ArtifactKey, ResolvedDependency> runtimeDeps = new HashMap<>();
    /** Dependent of each runtime dependency on a shortest path from the application, {@code null} for direct ones. */
    private final Map<ArtifactKey, ArtifactKey> dependents = new HashMap<>();

    /** Dependencies with no reachable classes whose exclusion removes only dependencies with no reachable classes. */
    private final List<JarTreeShakeDependencyInfo> removable = new ArrayList<>();
    /** Dependencies that would leave the classpath if the key were excluded, for each of {@link #removable}. */
    private final Map<ArtifactKey, Set<ArtifactKey>> removedWith = new HashMap<>();
    /** Dependencies with no reachable classes whose exclusion would also remove dependencies with reachable classes. */
    private final List<JarTreeShakeDependencyInfo> unusedWithUsedDeps = new ArrayList<>();
    private final Map<ArtifactKey, List<ArtifactKey>> usedRemovedWith = new HashMap<>();
    private final List<JarTreeShakeDependencyInfo> resourcesOnly = new ArrayList<>();
    private final List<JarTreeShakeDependencyInfo> barelyUsed = new ArrayList<>();
    private final List<JarTreeShakeDependencyInfo> excluded = new ArrayList<>();
    private final List<JarTreeShakeDependencyInfo> duplicates = new ArrayList<>();

    DependencyTrimReport(JarTreeShakeBuildItem treeShake, ApplicationModel appModel, int barelyUsedMaxClasses,
            int maxListedClasses) {
        this.treeShake = treeShake;
        this.appModel = appModel;
        this.barelyUsedMaxClasses = barelyUsedMaxClasses;
        this.maxListedClasses = maxListedClasses;
    }

    List<String> render() {
        for (ResolvedDependency dep : appModel.getDependencies(DependencyFlags.RUNTIME_CP)) {
            runtimeDeps.put(dep.getKey(), dep);
        }
        indexDependents();
        categorize();

        final List<String> lines = new ArrayList<>();
        addSummary(lines);
        addTree(lines);
        addUnusedWithUsedDeps(lines);
        addBarelyUsed(lines);
        addNotAnalyzed(lines);
        addCaveats(lines);
        return lines;
    }

    /**
     * Records, for each runtime dependency, its dependent on a shortest path from the application,
     * by a breadth-first walk of the dependency graph starting from the direct dependencies.
     */
    private void indexDependents() {
        final Queue<ArtifactKey> queue = new ArrayDeque<>();
        for (ArtifactCoords direct : appModel.getAppArtifact().getDependencies()) {
            final ArtifactKey key = direct.getKey();
            if (runtimeDeps.containsKey(key) && !dependents.containsKey(key)) {
                dependents.put(key, null);
                queue.add(key);
            }
        }
        while (!queue.isEmpty()) {
            final ArtifactKey parent = queue.poll();
            for (ArtifactCoords child : runtimeDeps.get(parent).getDependencies()) {
                final ArtifactKey key = child.getKey();
                if (runtimeDeps.containsKey(key) && !dependents.containsKey(key)) {
                    dependents.put(key, parent);
                    queue.add(key);
                }
            }
        }
    }

    private void categorize() {
        final List<JarTreeShakeDependencyInfo> unused = new ArrayList<>();
        for (JarTreeShakeDependencyInfo dep : treeShake.getDependencies().values()) {
            if (dep.excludedFromTreeShaking()) {
                excluded.add(dep);
            } else if (dep.resourcesOnly()) {
                resourcesOnly.add(dep);
            } else if (dep.originalClassCount() == 0) {
                duplicates.add(dep);
            } else if (dep.allClassesRemoved()) {
                unused.add(dep);
            } else if (!dep.removedClasses().isEmpty() && dep.keptClassCount() <= barelyUsedMaxClasses) {
                barelyUsed.add(dep);
            }
        }
        categorizeUnused(unused);
        removable.sort(BY_COORDS);
        unusedWithUsedDeps.sort(BY_COORDS);
        resourcesOnly.removeIf(dep -> isRemovedWithOther(dep.coords().getKey()));
        resourcesOnly.sort(BY_COORDS);
        barelyUsed.sort(Comparator.comparingInt(JarTreeShakeDependencyInfo::keptClassCount).thenComparing(BY_COORDS));
        excluded.sort(BY_COORDS);
        duplicates.sort(BY_COORDS);
    }

    /**
     * Splits the dependencies with no reachable classes into the ones whose exclusion would remove only
     * dependencies with no reachable classes, keeping the top-most of those, and the ones whose exclusion would also
     * remove dependencies with reachable classes.
     */
    private void categorizeUnused(List<JarTreeShakeDependencyInfo> unused) {
        for (JarTreeShakeDependencyInfo dep : unused) {
            final ArtifactKey key = dep.coords().getKey();
            if (!dependents.containsKey(key)) {
                // not reachable from the application in the dependency graph
                removable.add(dep);
                removedWith.put(key, Set.of(key));
                continue;
            }
            final Set<ArtifactKey> removed = removedWithExclusionOf(key);
            final List<ArtifactKey> used = new ArrayList<>();
            for (ArtifactKey removedKey : removed) {
                if (!hasNoReachableClasses(treeShake.getDependencies().get(removedKey))) {
                    used.add(removedKey);
                }
            }
            if (used.isEmpty()) {
                removable.add(dep);
                removedWith.put(key, removed);
            } else {
                used.sort(Comparator.comparing(ArtifactKey::toGacString));
                unusedWithUsedDeps.add(dep);
                usedRemovedWith.put(key, used);
            }
        }
        // a dependency removed with another one is listed in the tree of the other one
        removable.removeIf(dep -> isRemovedWithOther(dep.coords().getKey()));
        removedWith.keySet().retainAll(removable.stream().map(dep -> dep.coords().getKey()).toList());
    }

    /**
     * Returns the runtime dependencies, including the given one, that would no longer be reachable from the
     * application if the given dependency were excluded from the dependency graph.
     */
    private Set<ArtifactKey> removedWithExclusionOf(ArtifactKey excluded) {
        final Set<ArtifactKey> reachable = new HashSet<>();
        final Queue<ArtifactKey> queue = new ArrayDeque<>();
        for (ArtifactCoords direct : appModel.getAppArtifact().getDependencies()) {
            final ArtifactKey key = direct.getKey();
            if (!key.equals(excluded) && runtimeDeps.containsKey(key) && reachable.add(key)) {
                queue.add(key);
            }
        }
        while (!queue.isEmpty()) {
            for (ArtifactCoords child : runtimeDeps.get(queue.poll()).getDependencies()) {
                final ArtifactKey key = child.getKey();
                if (!key.equals(excluded) && runtimeDeps.containsKey(key) && reachable.add(key)) {
                    queue.add(key);
                }
            }
        }
        final Set<ArtifactKey> removed = new HashSet<>();
        for (ArtifactKey key : dependents.keySet()) {
            if (!reachable.contains(key)) {
                removed.add(key);
            }
        }
        return removed;
    }

    private boolean isRemovedWithOther(ArtifactKey key) {
        for (Map.Entry<ArtifactKey, Set<ArtifactKey>> entry : removedWith.entrySet()) {
            if (!entry.getKey().equals(key) && entry.getValue().contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the analysis found no reachable classes in the dependency: all its classes are unreachable, it has
     * no classes, or its classes are all attributed to dependencies earlier on the classpath.
     */
    private static boolean hasNoReachableClasses(JarTreeShakeDependencyInfo dep) {
        return dep != null && !dep.excludedFromTreeShaking() && dep.keptClassCount() == 0;
    }

    private void addSummary(List<String> lines) {
        int classes = 0;
        int kept = 0;
        for (JarTreeShakeDependencyInfo dep : treeShake.getDependencies().values()) {
            classes += dep.originalClassCount();
            kept += dep.keptClassCount();
        }
        lines.add("Dependency trim report for " + appModel.getAppArtifact().toCompactCoords());
        lines.add(INDENT + treeShake.getDependencies().size() + " runtime dependencies, " + kept + " of " + classes
                + " classes reachable");
    }

    /**
     * Adds the part of the dependency tree leading to the dependencies that could be trimmed, each dependency placed
     * on its shortest path from the application, annotated with what could be trimmed.
     */
    private void addTree(List<String> lines) {
        final Map<ArtifactKey, String> notes = treeNotes();
        final Map<ArtifactKey, List<ArtifactKey>> children = new HashMap<>();
        final List<ArtifactKey> notInGraph = new ArrayList<>();
        for (ArtifactKey key : notes.keySet()) {
            if (dependents.containsKey(key)) {
                addBranch(key, children);
            } else {
                notInGraph.add(key);
            }
        }
        lines.add("");
        if (notes.isEmpty()) {
            lines.add("No dependencies to trim were found.");
            return;
        }
        final List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { appModel.getAppArtifact().toCompactCoords(), null });
        addTreeRows(null, "", children, notes, rows);
        addAligned(lines, rows);
        addNotShown(lines, children);
        if (!notInGraph.isEmpty()) {
            lines.add("Not found in the dependency graph:");
            notInGraph.sort(Comparator.comparing(ArtifactKey::toGacString));
            for (ArtifactKey key : notInGraph) {
                lines.add(ITEM + describe(key) + "  " + notes.get(key));
            }
        }
    }

    /**
     * Returns the annotations of the dependencies that could be trimmed, which are the ones shown in the tree
     * together with their dependents.
     */
    private Map<ArtifactKey, String> treeNotes() {
        final Map<ArtifactKey, String> notes = new HashMap<>();
        for (JarTreeShakeDependencyInfo dep : removable) {
            final ArtifactKey key = dep.coords().getKey();
            final Set<ArtifactKey> removed = removedWith.get(key);
            int removedClasses = 0;
            for (ArtifactKey removedKey : removed) {
                removedClasses += treeShake.getDependencies().get(removedKey).originalClassCount();
                if (!removedKey.equals(key)) {
                    notes.put(removedKey, "no reachable classes (" + describeClasses(
                            treeShake.getDependencies().get(removedKey)) + ")");
                }
            }
            notes.put(key, (removed.size() == 1
                    ? "EXCLUDE: no reachable classes (" + classes(dep.originalClassCount()) + ")"
                    : "EXCLUDE: no reachable classes, with " + dependencies(removed.size() - 1) + " ("
                            + classes(removedClasses) + " in total)")
                    + otherDependents(key));
        }
        for (JarTreeShakeDependencyInfo dep : unusedWithUsedDeps) {
            notes.put(dep.coords().getKey(), "no reachable classes (" + classes(dep.originalClassCount())
                    + "), but excluding it would also remove dependencies with reachable classes, see below");
        }
        for (JarTreeShakeDependencyInfo dep : resourcesOnly) {
            notes.put(dep.coords().getKey(), "no classes, its resources may still be read at runtime");
        }
        return notes;
    }

    /**
     * Returns a note naming the dependents of the given dependency other than its parent in the tree, from which
     * it would have to be excluded as well, or an empty string if there are none. Dependents with no reachable
     * classes are left out, since they are trimming candidates themselves.
     */
    private String otherDependents(ArtifactKey key) {
        final ArtifactKey treeParent = dependents.get(key);
        final List<String> others = new ArrayList<>();
        if (treeParent != null && dependsOn(appModel.getAppArtifact(), key)) {
            others.add("the application");
        }
        for (ResolvedDependency dep : runtimeDeps.values()) {
            if (!dep.getKey().equals(treeParent) && dependents.containsKey(dep.getKey()) && dependsOn(dep, key)
                    && !hasNoReachableClasses(treeShake.getDependencies().get(dep.getKey()))) {
                others.add(dep.getKey().toGacString());
            }
        }
        if (others.isEmpty()) {
            return "";
        }
        others.sort(null);
        return "; also a dependency of " + String.join(", ", others);
    }

    private static boolean dependsOn(ResolvedDependency dep, ArtifactKey key) {
        for (ArtifactCoords child : dep.getDependencies()) {
            if (child.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the given dependency and its dependents up to the application to the tree.
     */
    private void addBranch(ArtifactKey key, Map<ArtifactKey, List<ArtifactKey>> children) {
        ArtifactKey child = key;
        while (true) {
            final ArtifactKey parent = dependents.get(child);
            final List<ArtifactKey> siblings = children.computeIfAbsent(parent, k -> new ArrayList<>());
            if (siblings.contains(child)) {
                return;
            }
            siblings.add(child);
            if (parent == null) {
                return;
            }
            child = parent;
        }
    }

    private void addTreeRows(ArtifactKey parent, String indent, Map<ArtifactKey, List<ArtifactKey>> children,
            Map<ArtifactKey, String> notes, List<String[]> rows) {
        final List<ArtifactKey> keys = children.get(parent);
        if (keys == null) {
            return;
        }
        keys.sort(Comparator.comparing(key -> runtimeDeps.get(key).toCompactCoords()));
        for (int i = 0; i < keys.size(); ++i) {
            final ArtifactKey key = keys.get(i);
            final boolean last = i == keys.size() - 1;
            rows.add(new String[] { indent + (last ? "└─ " : "├─ ") + describe(key), notes.get(key) });
            addTreeRows(key, indent + (last ? "   " : "│  "), children, notes, rows);
        }
    }

    /**
     * Adds the tree rows to the report with their annotations aligned.
     */
    private static void addAligned(List<String> lines, List<String[]> rows) {
        int width = 0;
        for (String[] row : rows) {
            if (row[1] != null) {
                width = Math.max(width, row[0].length());
            }
        }
        for (String[] row : rows) {
            if (row[1] == null) {
                lines.add(row[0]);
            } else {
                lines.add(row[0] + " ".repeat(width - row[0].length() + 2) + row[1]);
            }
        }
    }

    private void addNotShown(List<String> lines, Map<ArtifactKey, List<ArtifactKey>> children) {
        int direct = 0;
        for (ArtifactKey dependent : dependents.values()) {
            if (dependent == null) {
                ++direct;
            }
        }
        final int notShown = direct - children.getOrDefault(null, List.of()).size();
        if (notShown > 0) {
            lines.add("Not shown: " + (notShown == 1 ? "1 direct dependency" : notShown + " direct dependencies")
                    + " with nothing to trim. See quarkus:dependency-tree for the complete tree.");
        }
    }

    private static String dependencies(int count) {
        return count == 1 ? "1 dependency" : count + " dependencies";
    }

    private static String describeClasses(JarTreeShakeDependencyInfo dep) {
        if (dep.resourcesOnly()) {
            return "no classes";
        }
        if (dep.originalClassCount() == 0) {
            return "classes also in dependencies earlier on the classpath";
        }
        return classes(dep.originalClassCount());
    }

    private void addUnusedWithUsedDeps(List<String> lines) {
        if (unusedWithUsedDeps.isEmpty()) {
            return;
        }
        addHeading(lines, "Dependencies with no reachable classes, whose exclusion would also remove dependencies"
                + " with reachable classes", unusedWithUsedDeps.size());
        for (JarTreeShakeDependencyInfo dep : unusedWithUsedDeps) {
            lines.add(ITEM + describe(dep) + " (" + classes(dep.originalClassCount()) + ")");
            addPath(lines, dep);
            for (ArtifactKey used : usedRemovedWith.get(dep.coords().getKey())) {
                lines.add(DETAIL + "would also remove " + used.toGacString());
            }
        }
    }

    private void addBarelyUsed(List<String> lines) {
        addHeading(lines, "Dependencies with unreachable classes and at most " + classes(barelyUsedMaxClasses) + " reachable",
                barelyUsed.size());
        for (JarTreeShakeDependencyInfo dep : barelyUsed) {
            lines.add(ITEM + describe(dep) + " (" + dep.keptClassCount() + " of " + classes(dep.originalClassCount())
                    + " reachable)");
            addPath(lines, dep);
            addReachableClasses(lines, dep);
        }
    }

    private void addNotAnalyzed(List<String> lines) {
        if (excluded.isEmpty() && duplicates.isEmpty()) {
            return;
        }
        lines.add("");
        lines.add("Not analyzed:");
        for (JarTreeShakeDependencyInfo dep : excluded) {
            lines.add(ITEM + describe(dep) + " (excluded from tree shaking)");
        }
        for (JarTreeShakeDependencyInfo dep : duplicates) {
            lines.add(ITEM + describe(dep) + " (all classes also present in dependencies earlier on the classpath)");
        }
    }

    private static void addCaveats(List<String> lines) {
        lines.add("");
        lines.add("The report is based on static analysis and heuristics: test the application after removing"
                + " a dependency. Unless native is enabled, the results are for a JVM build;"
                + " a native build keeps more classes.");
    }

    private static String classes(int count) {
        return count == 1 ? "1 class" : count + " classes";
    }

    private static void addHeading(List<String> lines, String title, int count) {
        lines.add("");
        lines.add(title + ": " + (count == 0 ? "none" : count));
    }

    private String describe(JarTreeShakeDependencyInfo dep) {
        return describe(dep.coords().getKey());
    }

    private String describe(ArtifactKey key) {
        final ResolvedDependency resolved = runtimeDeps.get(key);
        if (resolved == null) {
            return key.toGacString();
        }
        final String coords = resolved.toCompactCoords();
        return resolved.isRuntimeExtensionArtifact() ? coords + " [Quarkus extension]" : coords;
    }

    /**
     * Adds the dependency path from a direct dependency of the application, where an exclusion would be added.
     */
    private void addPath(List<String> lines, JarTreeShakeDependencyInfo dep) {
        final ArtifactKey key = dep.coords().getKey();
        if (!dependents.containsKey(key)) {
            return;
        }
        ArtifactKey dependent = dependents.get(key);
        if (dependent == null) {
            lines.add(DETAIL + "direct dependency");
            return;
        }
        final StringBuilder path = new StringBuilder(key.toGacString());
        while (dependent != null) {
            path.insert(0, " -> ").insert(0, dependent.toGacString());
            dependent = dependents.get(dependent);
        }
        lines.add(DETAIL + "via " + path);
    }

    private void addReachableClasses(List<String> lines, JarTreeShakeDependencyInfo dep) {
        final ResolvedDependency resolved = runtimeDeps.get(dep.coords().getKey());
        if (resolved == null) {
            return;
        }
        final TreeSet<String> classes = new TreeSet<>();
        try {
            treeShake.collectReachableClasses(resolved, classes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resolved.toCompactCoords(), e);
        }
        int listed = 0;
        for (String className : classes) {
            if (listed++ == maxListedClasses) {
                lines.add(DETAIL + "... and " + (classes.size() - maxListedClasses) + " more");
                break;
            }
            lines.add(DETAIL + className);
        }
    }
}
