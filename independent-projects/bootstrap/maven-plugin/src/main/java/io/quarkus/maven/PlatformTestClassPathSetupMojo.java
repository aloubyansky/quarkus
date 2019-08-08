/**
 *
 */
package io.quarkus.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyResult;

import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.util.ZipUtils;

@Mojo(name = "platform-test-cp-setup", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PlatformTestClassPathSetupMojo extends AbstractMojo {

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     *
     * @component
     */
    @Component
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of artifacts and their dependencies.
     *
     * @parameter default-value="${project.remoteProjectRepositories}"
     * @readonly
     */
    @Parameter( defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true )
    private List<RemoteRepository> repos;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("PLATFORM TEST CP SETUP");

        final List<org.apache.maven.model.Dependency> projectManagedDep = project.getDependencyManagement().getDependencies();
        final List<Dependency> managedDeps = new ArrayList<>(projectManagedDep.size());
        for(org.apache.maven.model.Dependency d : projectManagedDep) {
            managedDeps.add(new Dependency(new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType(), d.getVersion()), d.getScope()));
        }

        try {
            MavenArtifactResolver resolver = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(repos)
                    .build();

            DependencyResult result = resolver.resolveManagedDependencies(
                    new DefaultArtifact(project.getGroupId(), project.getArtifactId(), project.getArtifact().getClassifier(),
                            project.getArtifact().getType(), project.getVersion()),
                    Collections.emptyList(), managedDeps, Collections.emptyList(), new String[0]);
            final Set<String> projectIds = new HashSet<>();
            collectIds(result.getRoot(), projectIds);

            result = resolver.resolveManagedDependencies(
                    new DefaultArtifact("org.apache.camel.quarkus", "camel-quarkus-integration-test-core", "", "jar",
                            "0.0.3-SNAPSHOT"),
                    Collections.emptyList(), managedDeps, Collections.emptyList(), new String[0]);

            final Path testClasses = Paths.get("").normalize().toAbsolutePath().resolve("target").resolve("imported-test-classes");
            if(!Files.exists(testClasses)) {
                try {
                    Files.createDirectories(testClasses);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to create directory " + testClasses, e);
                }
            }

            for(DependencyNode node : result.getRoot().getChildren()) {
                final Dependency dep = node.getDependency();
                if (dep == null) {
                    continue;
                }
                if (!dep.getScope().equals("test")) {
                    continue;
                }
                if (projectIds.contains(dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId() + ":"
                        + dep.getArtifact().getClassifier() + ":" + dep.getArtifact().getExtension())) {
                    continue;
                }
                importClasses(node, testClasses);
            }

            IoUtils.recursiveDelete(testClasses.resolve("META-INF").resolve("beans.xml"));
        } catch (AppModelResolverException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private static void collectIds(DependencyNode node, Set<String> ids) throws MojoExecutionException {
        org.eclipse.aether.artifact.Artifact a = node.getArtifact();
        ids.add(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getClassifier() + ":" + a.getExtension());
        final List<DependencyNode> children = node.getChildren();
        if(children.isEmpty()) {
            return;
        }
        for(DependencyNode child : children) {
            collectIds(child, ids);
        }
    }

    private static void importClasses(DependencyNode node, Path importedClassesDir) throws MojoExecutionException {
        final File file = node.getArtifact().getFile();
        if(file != null) {
            try {
                ZipUtils.unzip(file.toPath(), importedClassesDir);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to unzip " + file + " to " + importedClassesDir, e);
            }
        }
        final List<DependencyNode> children = node.getChildren();
        if(children.isEmpty()) {
            return;
        }
        for(DependencyNode child : children) {
            importClasses(child, importedClassesDir);
        }
    }
}
