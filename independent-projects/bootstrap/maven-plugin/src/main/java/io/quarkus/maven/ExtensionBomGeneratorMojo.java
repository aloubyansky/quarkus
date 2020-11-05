package io.quarkus.maven;

import io.quarkus.bom.decomposer.BomDecomposer;
import io.quarkus.bom.decomposer.BomDecomposerException;
import io.quarkus.bom.decomposer.DecomposedBom;
import io.quarkus.bom.decomposer.NoopDecomposedBomVisitor;
import io.quarkus.bom.decomposer.ProjectRelease;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;

@Mojo(name = "extension-bom", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class ExtensionBomGeneratorMojo extends AbstractMojo {

    @Component
    private RepositorySystem repoSystem;

    @Component
    private RemoteRepositoryManager remoteRepoManager;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repos;

    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        final Artifact platformBom = new DefaultArtifact("io.quarkus", "quarkus-bom", null, "pom", project.getVersion());

        final MavenArtifactResolver mvn;
        try {
            mvn = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(repos)
                    .setRemoteRepositoryManager(remoteRepoManager)
                    .setWorkspaceDiscovery(false)
                    .build();
        } catch (BootstrapMavenException e1) {
            throw new MojoExecutionException("Failed to initialize the Maven artifact resolver", e1);
        }

        final DecomposedBom decomposedQuarkusBom;
        try {
            decomposedQuarkusBom = decompose(platformBom, mvn);
        } catch (MojoExecutionException e1) {
            throw e1;
        }

        final List<Dependency> platformManagedDeps;
        try {
            platformManagedDeps = repoSystem
                    .readArtifactDescriptor(repoSession,
                            new ArtifactDescriptorRequest().setRepositories(repos).setArtifact(platformBom))
                    .getManagedDependencies();
        } catch (ArtifactDescriptorException e) {
            throw new MojoExecutionException("Failed to resolve the descriptor for " + platformBom, e);
        }

        final Map<AppArtifactKey, Dependency> managedKeys = new HashMap<>(platformManagedDeps.size());
        platformManagedDeps.stream().forEach(d -> managedKeys.put(new AppArtifactKey(d.getArtifact().getGroupId(),
                d.getArtifact().getArtifactId(), d.getArtifact().getClassifier(), d.getArtifact().getExtension()), d));

        Model model = initModel();
        final DependencyManagement dm = new DependencyManagement();
        model.setDependencyManagement(dm);

        org.apache.maven.model.Dependency self = new org.apache.maven.model.Dependency();
        self.setGroupId(project.getGroupId());
        self.setArtifactId(project.getArtifactId());
        self.setVersion(project.getVersion());
        dm.addDependency(self);

        final DecomposedBom decomposedDeps;
        try {
            final Set<org.apache.maven.artifact.Artifact> artifacts = project.getArtifacts();
            final List<Artifact> depList = new ArrayList<>(artifacts.size());
            artifacts.forEach(a -> {
                depList.add(new DefaultArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getType(),
                        a.getVersion()));
            });
            decomposedDeps = BomDecomposer.config()
                    .mavenArtifactResolver(mvn)
                    .bomArtifact(project.getGroupId(), project.getArtifactId(), project.getVersion())
                    .artifacts(depList)
                    .decompose();
        } catch (BomDecomposerException e1) {
            throw new MojoExecutionException("Failed to decompose project dependencies", e1);
        }

        try {
            decomposedDeps.visit(new NoopDecomposedBomVisitor() {
                @Override
                public void visitProjectRelease(ProjectRelease project) {
                    final ProjectRelease releaseBom = decomposedQuarkusBom.releaseOrNull(project.id());
                    if (releaseBom == null) {
                        return;
                    }

                    releaseBom.dependencies().forEach(d -> {
                        final Artifact a = d.artifact();
                        org.apache.maven.model.Dependency dep = new org.apache.maven.model.Dependency();
                        dep.setGroupId(a.getGroupId());
                        dep.setArtifactId(a.getArtifactId());
                        if (!StringUtils.isEmpty(a.getClassifier())) {
                            dep.setClassifier(a.getClassifier());
                        }
                        if (StringUtils.isNotEmpty(a.getExtension()) && !"jar".equals(a.getExtension())) {
                            dep.setType(a.getExtension());
                        }
                        dep.setVersion(a.getVersion());
                        /*
                         * if (!StringUtils.isEmpty(managedDep.getScope())) {
                         * dep.setScope(managedDep.getScope());
                         * }
                         * if (managedDep.isOptional()) {
                         * dep.setOptional(managedDep.isOptional());
                         * }
                         */
                        dm.getDependencies().add(dep);

                    });
                }
            });
        } catch (BomDecomposerException e1) {
            throw new MojoExecutionException("Failed to process project deps", e1);
        }
        /* @formatter:off
        project.getArtifacts().stream().forEach(a -> {

            final Dependency managedDep = managedKeys
                    .get(new AppArtifactKey(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getType()));
            getLog().info((managedDep != null ? "         " : "EXCLUDED ") + " " + a);
            if (managedDep == null) {
                return;
            }
            org.apache.maven.model.Dependency dep = new org.apache.maven.model.Dependency();
            dep.setGroupId(a.getGroupId());
            dep.setArtifactId(a.getArtifactId());
            if (!StringUtils.isEmpty(a.getClassifier())) {
                dep.setClassifier(a.getClassifier());
            }
            if (StringUtils.isNotEmpty(a.getType()) && !"jar".equals(a.getType())) {
                dep.setType(a.getType());
            }
            dep.setVersion(a.getVersion());
            if (!StringUtils.isEmpty(managedDep.getScope())) {
                dep.setScope(managedDep.getScope());
            }
            if (managedDep.isOptional()) {
                dep.setOptional(managedDep.isOptional());
            }
            dm.getDependencies().add(dep);
        });
        @formatter:on */

        Path bom = Paths.get(project.getBuild().getDirectory()).resolve("extension-bom.pom");
        try {
            Files.createDirectories(bom.getParent());
            ModelUtils.persistModel(bom, model);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to persist the model to " + bom, e);
        }

        this.projectHelper.attachArtifact(project, "xml", "bom", bom.toFile());
    }

    private Model initModel() throws MojoExecutionException {
        Model base = project.getModel();
        final Model model = new Model();
        model.setModelVersion(base.getModelVersion());
        model.setModelEncoding(base.getModelEncoding());
        model.setGroupId(base.getGroupId());
        model.setArtifactId(base.getArtifactId());
        model.setVersion(base.getVersion());
        model.setPackaging("pom");
        model.setName(base.getName());
        model.setDescription(base.getDescription());
        model.setUrl(base.getUrl());
        model.setDevelopers(base.getDevelopers());
        model.setLicenses(base.getLicenses());
        model.setScm(base.getScm());
        model.setCiManagement(base.getCiManagement());
        model.setIssueManagement(base.getIssueManagement());
        model.setDistributionManagement(base.getDistributionManagement());
        return model;
    }

    private DecomposedBom decompose(final Artifact platformBom, MavenArtifactResolver mvn) throws MojoExecutionException {
        try {
            return BomDecomposer.config().bomArtifact(platformBom).mavenArtifactResolver(mvn).decompose();
        } catch (BomDecomposerException e) {
            throw new MojoExecutionException("Failed to decompose BOM " + platformBom, e);
        }
    }
}
