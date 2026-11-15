package io.quarkus.test.junit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;

/**
 * Tests {@link AppMakerHelper#deriveApplication(CuratedApplication, List, String)}, which creates the application of
 * a test profile from the primary application of a test run.
 */
class AppMakerHelperDeriveTest {

    @TempDir
    Path primaryDir;

    @TempDir
    Path profileDir;

    private CuratedApplication primary;
    private CuratedApplication derived;

    @BeforeEach
    void createPrimary() throws Exception {
        primary = QuarkusBootstrap.builder()
                .setExistingModel(new ApplicationModelBuilder()
                        .setAppArtifact(ResolvedDependencyBuilder.newInstance()
                                .setGroupId("org.acme")
                                .setArtifactId("app")
                                .setVersion("1.0")
                                .setResolvedPath(primaryDir))
                        .setPlatformImports(PlatformImports.fromMap(Collections.emptyMap()))
                        .build())
                .setApplicationRoot(PathList.of(primaryDir))
                .setProjectRoot(primaryDir)
                .setMode(QuarkusBootstrap.Mode.TEST)
                .setTest(true)
                .setIsolateDeployment(true)
                .setBaseName("JUnitprimary (QuarkusTest)")
                .build()
                .bootstrap();
    }

    @AfterEach
    void closeApplications() {
        if (derived != null) {
            derived.close();
        }
        primary.close();
    }

    @Test
    void derivedApplicationAppendsAdditionalPathsToPrimaryRoots() {
        derived = AppMakerHelper.deriveApplication(primary, List.of(profileDir), "JUnitprofile");

        assertThat(derived.getQuarkusBootstrap().getApplicationRoot()).containsExactly(primaryDir, profileDir);
        assertThat(derived.getQuarkusBootstrap().getBaseName()).isEqualTo("JUnitprofile (QuarkusTest)");
        assertThat(derived.getOrCreateAugmentClassLoader()).isSameAs(primary.getOrCreateAugmentClassLoader());
    }

    @Test
    void missingAndDuplicateAdditionalPathsAreSkipped() {
        derived = AppMakerHelper.deriveApplication(primary,
                List.of(primaryDir, profileDir.resolve("does-not-exist")), "JUnitprofile");

        assertThat(derived.getQuarkusBootstrap().getApplicationRoot()).containsExactly(primaryDir);
    }
}
