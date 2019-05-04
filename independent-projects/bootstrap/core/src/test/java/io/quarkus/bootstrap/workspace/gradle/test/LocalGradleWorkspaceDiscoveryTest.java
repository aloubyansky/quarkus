/**
 *
 */
package io.quarkus.bootstrap.workspace.gradle.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.LocalProject;
import io.quarkus.bootstrap.resolver.gradle.workspace.LocalGradleProject;
import io.quarkus.bootstrap.util.IoUtils;

public class LocalGradleWorkspaceDiscoveryTest {
    public static final String DEFAULT_GROUP_ID = "io.quarkus.test";
    public static final String DEFAULT_VERSION = "1.0";

    protected static Path workDir;

    @BeforeClass
    public static void setup() throws Exception {
        workDir = IoUtils.createRandomTmpDir();
        IoUtils.copy(Paths.get("test/gradle-workspace/simple"), workDir);
    }

    @AfterClass
    public static void cleanup() {
        IoUtils.recursiveDelete(workDir);
    }

    @Test
    public void loadSimpleProject() throws Exception {
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

    /*
    @Test
    public void loadModuleProjectWithoutParent() throws Exception {
        final LocalProject project = LocalProject.load(workDir.resolve("root").resolve("module1").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-no-parent-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
    }

    @Test
    public void loadWorkspaceForModuleWithoutParent() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(workDir.resolve("root").resolve("module1").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-no-parent-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        assertNotNull(project.getWorkspace());
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(1, projects.size());
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module"));
    }

    @Test
    public void loadModuleProjectWithParent() throws Exception {
        final LocalProject project = LocalProject.load(workDir.resolve("root").resolve("module2").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-with-parent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
    }

    @Test
    public void loadWorkspaceForModuleWithParent() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(workDir.resolve("root").resolve("module2").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-with-parent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);
    }

    @Test
    public void loadWorkspaceForModuleWithNotDirectParentPath() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(workDir.resolve("root").resolve("other").resolve("module3").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-not-direct-child", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);
    }

    private void assertCompleteWorkspace(final LocalProject project) {
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(4, projects.size());
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-with-parent"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-not-direct-parent"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root"));
    }

    @Test
    public void loadNonModuleChildProject() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(workDir.resolve("root").resolve("non-module-child").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals("non-module-child", project.getArtifactId());
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(6, projects.size());
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-with-parent"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-not-direct-parent"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "non-module-child"));
        projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "another-child"));
    }
*/
}
