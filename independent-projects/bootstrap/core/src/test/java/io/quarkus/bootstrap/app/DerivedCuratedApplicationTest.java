package io.quarkus.bootstrap.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathList;

/**
 * Tests {@link CuratedApplication#derive(PathCollection, String)}: a derived application shares the augmentation
 * class loader of its parent and never closes it.
 */
class DerivedCuratedApplicationTest {

    @TempDir
    Path parentDir;

    @TempDir
    Path profileDir;

    private final List<CuratedApplication> applications = new ArrayList<>();

    @AfterEach
    void closeApplications() {
        // derived applications are closed before their parent
        Collections.reverse(applications);
        applications.forEach(CuratedApplication::close);
    }

    @Test
    void compatibleConfigYieldsDerivedApplicationSharingTheLoaderAndModel() throws Exception {
        final CuratedApplication parent = parent();
        final CuratedApplication derived = derive(parent);

        assertThat(derived).isInstanceOf(DerivedCuratedApplication.class);
        assertThat(derived.getOrCreateAugmentClassLoader()).isSameAs(parent.getOrCreateAugmentClassLoader());
        assertThat(derived.getAugmentClassLoader()).isSameAs(parent.getAugmentClassLoader());
        assertThat(derived.getApplicationModel()).isSameAs(parent.getApplicationModel());
        assertThat(derived.getQuarkusBootstrap().getApplicationRoot()).containsExactly(parentDir, profileDir);
        assertThat(derived.getQuarkusBootstrap().getBaseName()).isEqualTo("derived");
        assertThat(derived.getQuarkusBootstrap().isTest()).isTrue();
        assertThat(derived.getQuarkusBootstrap().isIsolateDeployment()).isTrue();
    }

    @Test
    void derivedApplicationCarriesOptionsNotCopiedByClonedBuilder() throws Exception {
        final CuratedApplication parent = register(parentBuilder()
                .setAuxiliaryApplication(true)
                .setHostApplicationIsTestOnly(true)
                .setFlatClassPath(true)
                .build().bootstrap());
        final CuratedApplication derived = derive(parent);

        assertThat(derived.getQuarkusBootstrap().isAuxiliaryApplication()).isTrue();
        assertThat(derived.getQuarkusBootstrap().isHostApplicationIsTestOnly()).isTrue();
        assertThat(derived.isFlatClassPath()).isTrue();
    }

    @Test
    void closingDerivedApplicationKeepsParentLoaderOpen() throws Exception {
        final CuratedApplication parent = parent();
        final CuratedApplication derived = derive(parent);
        final QuarkusClassLoader shared = derived.getOrCreateAugmentClassLoader();
        final QuarkusClassLoader base = derived.getOrCreateBaseRuntimeClassLoader();

        derived.close();

        assertThat(shared.isClosed()).isFalse();
        assertThat(base.isClosed()).isTrue();
        assertThat(parent.getAugmentClassLoader()).isSameAs(shared);
    }

    @Test
    void incompatibleConfigYieldsStandaloneApplication() throws Exception {
        Files.writeString(profileDir.resolve("application.properties"),
                "quarkus.class-loading.removed-artifacts=org.acme:lib");
        final CuratedApplication parent = parent();
        final CuratedApplication derived = derive(parent);

        assertThat(derived).isNotInstanceOf(DerivedCuratedApplication.class);
        final QuarkusClassLoader own = derived.getOrCreateAugmentClassLoader();
        assertThat(own).isNotSameAs(parent.getOrCreateAugmentClassLoader());

        derived.close();

        assertThat(own.isClosed()).isTrue();
        assertThat(parent.getAugmentClassLoader().isClosed()).isFalse();
    }

    @Test
    void closingParentClosesTheSharedLoader() throws Exception {
        final CuratedApplication parent = parent();
        final QuarkusClassLoader shared = parent.getOrCreateAugmentClassLoader();

        parent.close();

        assertThat(shared.isClosed()).isTrue();
    }

    private CuratedApplication parent() throws Exception {
        return register(parentBuilder().build().bootstrap());
    }

    /**
     * Derives an application whose roots are the parent directory followed by the profile directory.
     */
    private CuratedApplication derive(CuratedApplication parent) {
        return register(parent.derive(PathList.of(parentDir, profileDir), "derived"));
    }

    private CuratedApplication register(CuratedApplication application) {
        applications.add(application);
        return application;
    }

    private QuarkusBootstrap.Builder parentBuilder() throws IOException {
        return QuarkusBootstrap.builder()
                .setExistingModel(minimalModel())
                .setApplicationRoot(PathList.of(parentDir))
                .setProjectRoot(parentDir)
                .setMode(QuarkusBootstrap.Mode.TEST)
                .setTest(true)
                .setIsolateDeployment(true)
                .setBaseName("parent");
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
                        .setResolvedPath(parentDir))
                .setPlatformImports(PlatformImports.fromMap(Collections.emptyMap()))
                .build();
    }
}
