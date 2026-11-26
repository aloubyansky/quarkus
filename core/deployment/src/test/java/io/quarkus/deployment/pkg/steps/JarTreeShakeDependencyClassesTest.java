package io.quarkus.deployment.pkg.steps;

import static io.quarkus.deployment.pkg.steps.TreeShakeInputFixture.classReferencing;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.deployment.pkg.TreeShakeConfig;
import io.quarkus.deployment.pkg.builditem.JarTreeShakeDependencyInfo;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.smallrye.classfile.ClassFile;
import io.smallrye.classfile.attribute.ModuleAttribute;
import io.smallrye.classfile.extras.constant.ModuleDesc;

/**
 * Tests for the per-dependency class counts and the dependencies without classes reported by the analysis.
 */
class JarTreeShakeDependencyClassesTest {

    private static final String LIB = "org.acme:lib";
    private static final String OTHER = "org.acme:other";
    private static final String EXCLUDED = "org.acme:excluded";

    @TempDir
    Path dir;

    @Test
    void dependencyInfoCountsRemovedAndKeptClasses() {
        JarTreeShakeInput input = TreeShakeInputFixture.builder()
                .application(classReferencing("app.App", "lib.Used"))
                .dependency(LIB, classReferencing("lib.Used"), classReferencing("lib.Unused"))
                .dependency(OTHER, classReferencing("other.Unused"))
                .dependency(EXCLUDED, classReferencing("excluded.Unused"))
                .excludedArtifact(EXCLUDED)
                .build();

        Map<ArtifactKey, JarTreeShakeDependencyInfo> dependencies = new JarTreeShaker(input).run().getDependencies();

        assertThat(dependencies).containsOnlyKeys(key(LIB), key(OTHER), key(EXCLUDED));
        JarTreeShakeDependencyInfo lib = dependencies.get(key(LIB));
        assertThat(lib.coords().getKey()).isEqualTo(key(LIB));
        assertThat(lib.resourcesOnly()).isFalse();
        assertThat(lib.excludedFromTreeShaking()).isFalse();
        assertThat(lib.originalClassCount()).isEqualTo(2);
        assertThat(lib.removedClasses()).containsExactly("lib/Unused.class");
        assertThat(lib.keptClassCount()).isEqualTo(1);
        assertThat(lib.allClassesRemoved()).isFalse();

        JarTreeShakeDependencyInfo other = dependencies.get(key(OTHER));
        assertThat(other.removedClasses()).containsExactly("other/Unused.class");
        assertThat(other.allClassesRemoved()).isTrue();

        JarTreeShakeDependencyInfo excluded = dependencies.get(key(EXCLUDED));
        assertThat(excluded.excludedFromTreeShaking()).isTrue();
        assertThat(excluded.originalClassCount()).isEqualTo(1);
        assertThat(excluded.removedClasses()).isEmpty();
    }

    @Test
    void resourcesOnlyDependenciesAreCollected() throws IOException {
        ApplicationModelBuilder model = new ApplicationModelBuilder()
                .setAppArtifact(ResolvedDependencyBuilder.newInstance()
                        .setGroupId("org.acme")
                        .setArtifactId("app")
                        .setVersion("1.0"));
        addDependency(model, "classes", "lib/Foo.class", "META-INF/foo.properties");
        addDependency(model, "resources", "META-INF/foo.properties", "lib/data.json");
        addDependency(model, "module-info-only", "module-info.class", "META-INF/versions/11/module-info.class");
        addDependency(model, "invalid-version-only", "META-INF/versions/x/lib/Foo.class");
        addDependency(model, "newer-version-only", "META-INF/versions/99/lib/Newer.class");

        try (JarTreeShakeInput input = new JarTreeShakeInput(TreeShakeConfig.TreeShakeMode.CLASSES, 17)) {
            input.collectRuntimeClasses(model.build());

            assertThat(input.resourcesOnlyDependencies).containsExactlyInAnyOrder(
                    ArtifactKey.fromString("org.acme:resources"),
                    ArtifactKey.fromString("org.acme:module-info-only"),
                    ArtifactKey.fromString("org.acme:invalid-version-only"));
            assertThat(input.runtimeDependencies).extracting(ArtifactCoords::getArtifactId)
                    .containsExactly("classes", "resources", "module-info-only", "invalid-version-only",
                            "newer-version-only");
        }
    }

    private static ArtifactKey key(String artifactKey) {
        return ArtifactKey.fromString(artifactKey);
    }

    private void addDependency(ApplicationModelBuilder model, String artifactId, String... entries) throws IOException {
        Path root = dir.resolve(artifactId);
        Files.createDirectories(root);
        for (String entry : entries) {
            Path file = root.resolve(entry);
            Files.createDirectories(file.getParent());
            Files.write(file, JarTreeShakeInput.isModuleInfoEntry(entry) ? moduleDescriptor() : new byte[0]);
        }
        model.addDependency(ResolvedDependencyBuilder.newInstance()
                .setGroupId("org.acme")
                .setArtifactId(artifactId)
                .setVersion("1.0")
                .setResolvedPath(root)
                .setFlags(DependencyFlags.RUNTIME_CP | DependencyFlags.DEPLOYMENT_CP));
    }

    private static byte[] moduleDescriptor() {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("org.acme.lib"),
                module -> module.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)));
    }
}
