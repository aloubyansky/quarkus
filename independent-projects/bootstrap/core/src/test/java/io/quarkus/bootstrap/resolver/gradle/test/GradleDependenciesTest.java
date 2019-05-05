/**
 *
 */
package io.quarkus.bootstrap.resolver.gradle.test;

import static io.quarkus.bootstrap.workspace.gradle.test.GradleTestdataSimpleProject.cleanupSimple;
import static io.quarkus.bootstrap.workspace.gradle.test.GradleTestdataSimpleProject.setupSimple;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.resolver.BuilderInfo;
import io.quarkus.bootstrap.resolver.gradle.GradleBuilderInfo;
import io.quarkus.bootstrap.util.IoUtils;

public class GradleDependenciesTest {
    protected static Path workDir;

    @BeforeClass
    public static void setup() throws Exception {
        workDir = IoUtils.createRandomTmpDir();
        setupSimple(workDir);
    }

    @AfterClass
    public static void cleanup() {
        cleanupSimple(workDir);
    }

    @Test
    public void canExtractDependenciesFromSimpleProject() throws Exception {
        try (BuilderInfo bi = new GradleBuilderInfo(workDir)) {
            List<AppDependency> deps = bi.getDeploymentDependencies(true);
            
            assertThat(deps)
                .hasSizeGreaterThan(40);
            
            assertThat(deps)
                .extracting(ad -> ad.getArtifact().getArtifactId())
                .contains("assertj-core", "quarkus-resteasy");
        }
    }
}
