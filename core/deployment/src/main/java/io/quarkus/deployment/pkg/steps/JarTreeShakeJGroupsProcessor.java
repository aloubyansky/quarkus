package io.quarkus.deployment.pkg.steps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeRootClassBuildItem;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.OpenPathTree;

/**
 * Contributes JGroups class mapping entries as tree-shake root classes.
 * JGroups' {@code ClassConfigurator} eagerly loads all classes listed in
 * {@code jg-magic-map.xml} and {@code jg-protocol-ids.xml} during static
 * initialization, so they must be preserved regardless of reachability.
 */
public class JarTreeShakeJGroupsProcessor {

    /**
     * Looks up {@code jg-magic-map.xml} and {@code jg-protocol-ids.xml} in dependency JARs
     * and extracts class names that must be preserved. JGroups' {@code ClassConfigurator}
     * loads these classes by name during static initialization.
     */
    @BuildStep
    void collectJGroupsMagicMapRoots(
            CurateOutcomeBuildItem curateOutcome,
            BuildProducer<JarTreeShakeRootClassBuildItem> roots) {
        for (ResolvedDependency dep : curateOutcome.getApplicationModel().getDependencies(DependencyFlags.RUNTIME_CP)) {
            try (OpenPathTree openTree = dep.getContentTree().open()) {
                openTree.accept("jg-magic-map.xml", visit -> {
                    if (visit != null) {
                        parseJGroupsMagicMap(visit.getPath(), roots);
                    }
                });
                openTree.accept("jg-protocol-ids.xml", visit -> {
                    if (visit != null) {
                        parseJGroupsMagicMap(visit.getPath(), roots);
                    }
                });
            } catch (IOException e) {
                // ignore — dependency doesn't contain JGroups config
            }
        }
    }

    private static void parseJGroupsMagicMap(java.nio.file.Path file,
            BuildProducer<JarTreeShakeRootClassBuildItem> roots) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int nameIdx = line.indexOf("name=\"");
                if (nameIdx >= 0) {
                    int start = nameIdx + 6;
                    int end = line.indexOf('"', start);
                    if (end > start) {
                        roots.produce(new JarTreeShakeRootClassBuildItem(line.substring(start, end)));
                    }
                }
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
