package io.quarkus.bootstrap.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;

/**
 * Tests {@link ConfiguredClassLoading#isCompatibleForAugmentationWith(ConfiguredClassLoading)}, which decides
 * whether two applications can share an augmentation class loader.
 */
class ConfiguredClassLoadingCompatibilityTest {

    @TempDir
    Path firstRoot;

    @TempDir
    Path secondRoot;

    @Test
    void sameConfigIsCompatible() {
        assertCompatible(true);
    }

    @Test
    void differentRemovedArtifactsAreIncompatible() throws IOException {
        writeProperties(secondRoot, "quarkus.class-loading.removed-artifacts=org.acme:lib");
        assertCompatible(false);
    }

    @Test
    void differentParentFirstArtifactsAreIncompatible() throws IOException {
        writeProperties(secondRoot, "quarkus.class-loading.parent-first-artifacts=org.acme:lib");
        assertCompatible(false);
    }

    @Test
    void differentReloadableArtifactsAreIncompatible() throws IOException {
        writeProperties(secondRoot, "quarkus.class-loading.reloadable-artifacts=org.acme:lib");
        assertCompatible(false);
    }

    @Test
    void differentRemovedResourcesAreIncompatible() throws IOException {
        writeProperties(secondRoot, "quarkus.class-loading.removed-resources.\"org.acme\\:lib\"=META-INF/x");
        assertCompatible(false);
    }

    @Test
    void unrelatedPropertiesAreCompatible() throws IOException {
        writeProperties(secondRoot, "quarkus.http.port=8082");
        assertCompatible(true);
    }

    /**
     * Asserts the outcome of the compatibility check between the two roots, in both directions.
     */
    private void assertCompatible(boolean expected) {
        final ConfiguredClassLoading first = classLoading(firstRoot);
        final ConfiguredClassLoading second = classLoading(secondRoot);
        assertThat(first.isCompatibleForAugmentationWith(second)).isEqualTo(expected);
        assertThat(second.isCompatibleForAugmentationWith(first)).isEqualTo(expected);
    }

    private static void writeProperties(Path root, String line) throws IOException {
        Files.writeString(root.resolve("application.properties"), line + System.lineSeparator());
    }

    private ConfiguredClassLoading classLoading(Path root) {
        return ConfiguredClassLoading.builder()
                .setApplicationRoot(PathList.of(root))
                .setMode(QuarkusBootstrap.Mode.TEST)
                .setApplicationModel(minimalModel())
                .build();
    }

    /**
     * An application model with an application artifact and no dependencies.
     */
    private ApplicationModel minimalModel() {
        return new ApplicationModelBuilder()
                .setAppArtifact(ResolvedDependencyBuilder.newInstance()
                        .setGroupId("org.acme")
                        .setArtifactId("app")
                        .setVersion("1.0")
                        .setResolvedPath(firstRoot))
                .setPlatformImports(PlatformImports.fromMap(Collections.emptyMap()))
                .build();
    }
}
