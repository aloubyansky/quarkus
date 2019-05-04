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
import io.quarkus.bootstrap.resolver.LocalProject;
import io.quarkus.bootstrap.resolver.gradle.GradleArtifactResolver;
import io.quarkus.bootstrap.resolver.gradle.workspace.LocalGradleProject;
import io.quarkus.bootstrap.util.IoUtils;

public class GradleArtifactsResolverTest {
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
        final LocalProject project = LocalGradleProject.load(workDir.resolve("build").resolve("classes"));
        List<AppDependency> deps = new GradleArtifactResolver().getDeploymentDependencies(true, project);
        
        assertThat(deps)
            .hasSizeGreaterThan(40)
            .anySatisfy(dep ->
                assertThat(dep.getArtifact()).matches(a -> a.getGroupId().equals("org.assertj"), "should include assertj"));
    }
}
