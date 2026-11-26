package io.quarkus.deployment.pkg.steps;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeDependencyInfo;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;

class DependencyTrimReportTest {

    private static final ArtifactCoords DIRECT = ArtifactCoords.jar("org.acme", "direct", "1.0");
    private static final ArtifactCoords UNUSED = ArtifactCoords.jar("org.acme", "unused", "1.0");
    private static final ArtifactCoords RESOURCES = ArtifactCoords.jar("org.acme", "resources", "1.0");
    private static final ArtifactCoords UTILS = ArtifactCoords.jar("org.acme", "utils", "1.0");
    private static final ArtifactCoords USED = ArtifactCoords.jar("org.acme", "used", "1.0");
    private static final ArtifactCoords EXCLUDED = ArtifactCoords.jar("org.acme", "excluded", "1.0");
    private static final ArtifactCoords EXTENSION = ArtifactCoords.jar("org.acme", "extension", "1.0");
    private static final ArtifactCoords UNUSED_CHILD = ArtifactCoords.jar("org.acme", "unused-child", "1.0");
    private static final ArtifactCoords UNUSED_DATA = ArtifactCoords.jar("org.acme", "unused-data", "1.0");
    private static final ArtifactCoords BLOCKER = ArtifactCoords.jar("org.acme", "blocker", "1.0");
    private static final ArtifactCoords BLOCKED = ArtifactCoords.jar("org.acme", "blocked", "1.0");

    @TempDir
    Path dir;

    private final ApplicationModelBuilder model = new ApplicationModelBuilder();
    private final Map<ArtifactKey, JarTreeShakeDependencyInfo> dependencies = new LinkedHashMap<>();

    @Test
    void reportsCandidatesWithTheirPaths() throws IOException {
        model.setAppArtifact(ResolvedDependencyBuilder.newInstance()
                .setCoords(ArtifactCoords.jar("org.acme", "app", "1.0"))
                .addDependency(DIRECT)
                .addDependency(EXCLUDED)
                .addDependency(EXTENSION)
                .addDependency(BLOCKER));
        addDependency(DIRECT, 0, List.of(UNUSED, RESOURCES, UTILS, USED), "org/acme/direct/Direct.class");
        addDependency(UNUSED, 0, List.of(UNUSED_CHILD, USED), "org/acme/unused/A.class", "org/acme/unused/B.class");
        addDependency(UNUSED_CHILD, 0, List.of(UNUSED_DATA), "org/acme/unusedchild/C.class");
        addDependency(UNUSED_DATA, 0, List.of(), "META-INF/data.json");
        addDependency(BLOCKER, 0, List.of(BLOCKED), "org/acme/blocker/Blocker.class");
        addDependency(BLOCKED, 0, List.of(), "org/acme/blocked/Blocked.class");
        addDependency(RESOURCES, 0, List.of(), "META-INF/data.json");
        addDependency(UTILS, 0, List.of(), "org/acme/utils/Kept.class", "org/acme/utils/Unused1.class",
                "org/acme/utils/Unused2.class");
        addDependency(USED, 0, List.of(), "org/acme/used/Used.class");
        addDependency(EXCLUDED, 0, List.of(), "org/acme/excluded/Excluded.class");
        addDependency(EXTENSION, DependencyFlags.RUNTIME_EXTENSION_ARTIFACT, List.of(), "org/acme/ext/Ext.class");

        dependencies.put(DIRECT.getKey(), info(DIRECT, false, false, 1, List.of()));
        dependencies.put(UNUSED.getKey(), info(UNUSED, false, false, 2,
                List.of("org/acme/unused/A.class", "org/acme/unused/B.class")));
        dependencies.put(UNUSED_CHILD.getKey(), info(UNUSED_CHILD, false, false, 1, List.of("org/acme/unusedchild/C.class")));
        dependencies.put(UNUSED_DATA.getKey(), info(UNUSED_DATA, false, true, 0, List.of()));
        dependencies.put(BLOCKER.getKey(), info(BLOCKER, false, false, 1, List.of("org/acme/blocker/Blocker.class")));
        dependencies.put(BLOCKED.getKey(), info(BLOCKED, false, false, 1, List.of()));
        dependencies.put(RESOURCES.getKey(), info(RESOURCES, false, true, 0, List.of()));
        dependencies.put(UTILS.getKey(), info(UTILS, false, false, 3,
                List.of("org/acme/utils/Unused1.class", "org/acme/utils/Unused2.class")));
        dependencies.put(USED.getKey(), info(USED, false, false, 1, List.of()));
        dependencies.put(EXCLUDED.getKey(), info(EXCLUDED, true, false, 1, List.of()));
        dependencies.put(EXTENSION.getKey(), info(EXTENSION, false, false, 1, List.of("org/acme/ext/Ext.class")));

        List<String> report = render(Set.of("org.acme.direct.Direct", "org.acme.utils.Kept", "org.acme.used.Used",
                "org.acme.excluded.Excluded", "org.acme.blocked.Blocked"));

        assertThat(normalize(report)).containsSubsequence(
                "org.acme:app:1.0",
                "├─ org.acme:blocker:1.0 no reachable classes (1 class), but excluding it would also remove"
                        + " dependencies with reachable classes, see below",
                "├─ org.acme:direct:1.0",
                "│ ├─ org.acme:resources:1.0 no classes, its resources may still be read at runtime",
                "│ └─ org.acme:unused:1.0 EXCLUDE: no reachable classes, with 2 dependencies (3 classes in total)",
                // used is also a dependency of direct, so it isn't removed with unused nor shown
                "│ └─ org.acme:unused-child:1.0 no reachable classes (1 class)",
                "│ └─ org.acme:unused-data:1.0 no reachable classes (no classes)",
                "└─ org.acme:extension:1.0 [Quarkus extension] EXCLUDE: no reachable classes (1 class)",
                "Not shown: 1 direct dependency with nothing to trim. See quarkus:dependency-tree for the complete tree.",
                "Dependencies with no reachable classes, whose exclusion would also remove dependencies with"
                        + " reachable classes: 1",
                "- org.acme:blocker:1.0 (1 class)",
                "direct dependency",
                "would also remove org.acme:blocked",
                "Dependencies with unreachable classes and at most 1 class reachable: 1",
                "- org.acme:utils:1.0 (1 of 3 classes reachable)",
                "via org.acme:direct -> org.acme:utils",
                "org.acme.utils.Kept",
                "Not analyzed:",
                "- org.acme:excluded:1.0 (excluded from tree shaking)");
        // fully used dependencies are not candidates
        assertThat(report).noneMatch(line -> line.contains("org.acme:used:1.0"));
        // dependencies removed with another one are shown only in its tree
        assertThat(report).filteredOn(line -> line.contains("org.acme:unused-child:1.0")).hasSize(1);
        assertThat(report).filteredOn(line -> line.contains("org.acme:unused-data:1.0")).hasSize(1);
    }

    @Test
    void otherDependentsWithReachableClassesAreNamed() throws IOException {
        model.setAppArtifact(ResolvedDependencyBuilder.newInstance()
                .setCoords(ArtifactCoords.jar("org.acme", "app", "1.0"))
                .addDependency(DIRECT)
                .addDependency(USED));
        addDependency(DIRECT, 0, List.of(UNUSED), "org/acme/direct/Direct.class");
        addDependency(USED, 0, List.of(UNUSED), "org/acme/used/Used.class");
        addDependency(UNUSED, 0, List.of(), "org/acme/unused/A.class");
        dependencies.put(DIRECT.getKey(), info(DIRECT, false, false, 1, List.of()));
        dependencies.put(USED.getKey(), info(USED, false, false, 1, List.of()));
        dependencies.put(UNUSED.getKey(), info(UNUSED, false, false, 1, List.of("org/acme/unused/A.class")));

        List<String> report = render(Set.of("org.acme.direct.Direct", "org.acme.used.Used"));

        assertThat(normalize(report)).contains("└─ org.acme:unused:1.0 EXCLUDE: no reachable classes (1 class);"
                + " also a dependency of org.acme:used");
    }

    /**
     * Trims the lines and collapses the whitespace that aligns the annotations, so that the expected lines don't
     * depend on the column width.
     */
    private static List<String> normalize(List<String> report) {
        return report.stream().map(line -> line.trim().replaceAll(" {2,}", " ")).toList();
    }

    private List<String> render(Set<String> reachableClasses) {
        JarTreeShakeBuildItem treeShake = new JarTreeShakeBuildItem(true, reachableClasses, dependencies, Set.of());
        ApplicationModel appModel = model.build();
        return new DependencyTrimReport(treeShake, appModel, 1, 10).render();
    }

    private static JarTreeShakeDependencyInfo info(ArtifactCoords coords, boolean excluded, boolean resourcesOnly,
            int classCount, List<String> removed) {
        return new JarTreeShakeDependencyInfo(coords, excluded, resourcesOnly, classCount, new ArrayList<>(removed));
    }

    private void addDependency(ArtifactCoords coords, int flags, List<ArtifactCoords> deps, String... entries)
            throws IOException {
        Path root = dir.resolve(coords.getArtifactId());
        Files.createDirectories(root);
        for (String entry : entries) {
            Path file = root.resolve(entry);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }
        model.addDependency(ResolvedDependencyBuilder.newInstance()
                .setCoords(coords)
                .setResolvedPath(root)
                .setDependencies(deps)
                .setFlags(DependencyFlags.RUNTIME_CP | DependencyFlags.DEPLOYMENT_CP | flags));
    }
}
