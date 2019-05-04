/**
 *
 */
package io.quarkus.bootstrap.workspace.gradle.test;

import static io.quarkus.bootstrap.workspace.gradle.test.TestdataSimpleProject.cleanupSimple;
import static io.quarkus.bootstrap.workspace.gradle.test.TestdataSimpleProject.setupSimple;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Path;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.quarkus.bootstrap.resolver.LocalProject;
import io.quarkus.bootstrap.resolver.gradle.workspace.LocalGradleProject;
import io.quarkus.bootstrap.util.IoUtils;

public class LocalGradleProjectTest {
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
    public void extractClasspathFromSimpleProject() throws Exception {
        Path buildDir = workDir.resolve("build");
        final LocalProject project = LocalGradleProject.load(buildDir.resolve("classes"));
        assertNotNull(project);
        
        assertEquals(buildDir, project.getOutputDir());

    }
}
