/**
 *
 */
package io.quarkus.bootstrap.workspace.gradle.test;

import static io.quarkus.bootstrap.workspace.gradle.test.GradleTestdataSimpleProject.DEFAULT_GROUP_ID;
import static io.quarkus.bootstrap.workspace.gradle.test.GradleTestdataSimpleProject.DEFAULT_VERSION;
import static io.quarkus.bootstrap.workspace.gradle.test.GradleTestdataSimpleProject.cleanupSimple;
import static io.quarkus.bootstrap.workspace.gradle.test.GradleTestdataSimpleProject.setupSimple;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Path;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.quarkus.bootstrap.model.AppArtifactKey;
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
    public void canExtractOutputDirFromSimpleProject() throws Exception {
        Path buildDir = workDir.resolve("build");
        final LocalProject project = LocalGradleProject.load(buildDir.resolve("classes"));
        assertNotNull(project);
        
        assertEquals(buildDir, project.getOutputDir());
    }
    
    @Test
    public void canLoadSimpleProject() throws Exception {
        final LocalProject project = LocalGradleProject.load(workDir.resolve("build").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("simple", project.getArtifactId());
        assertEquals(DEFAULT_VERSION, project.getVersion());
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(1, projects.size());
        projects.containsKey(new AppArtifactKey(DEFAULT_GROUP_ID, "simple"));
    }
}
