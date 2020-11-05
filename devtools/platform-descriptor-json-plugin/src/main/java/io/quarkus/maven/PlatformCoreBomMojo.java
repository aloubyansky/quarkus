package io.quarkus.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.dependencies.Extension;
import io.quarkus.platform.descriptor.QuarkusPlatformDescriptor;
import io.quarkus.platform.descriptor.resolver.json.QuarkusJsonPlatformDescriptorResolver;

@Mojo(name = "platform-core-bom", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class PlatformCoreBomMojo extends AbstractMojo {

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

        final String bomGroupId = "io.quarkus";
        final String bomArtifactId = "quarkus-bom";
        final String bomVersion = project.getVersion();

        final Artifact descriptorArtifact = new DefaultArtifact(bomGroupId,
                bomArtifactId + BootstrapConstants.PLATFORM_DESCRIPTOR_ARTIFACT_ID_SUFFIX, bomVersion, "json", bomVersion);
        final Path descriptorPath;
        try {
            descriptorPath = repoSystem.resolveArtifact(repoSession, new ArtifactRequest()
                    .setArtifact(descriptorArtifact)
                    .setRepositories(repos)).getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to resolve platform descriptor ");
        }

        final Artifact platformBom = new DefaultArtifact(bomGroupId, bomArtifactId, null, "pom", bomVersion);

        final List<Dependency> platformManagedDeps;
        try {
            platformManagedDeps = repoSystem
                    .readArtifactDescriptor(repoSession,
                            new ArtifactDescriptorRequest().setRepositories(repos).setArtifact(platformBom))
                    .getManagedDependencies();
        } catch (ArtifactDescriptorException e) {
            throw new MojoExecutionException("Failed to resolve the descriptor for " + platformBom, e);
        }

        final Map<AppArtifactKey, Dependency> coreDeps = new HashMap<>(platformManagedDeps.size());
        platformManagedDeps.forEach(d -> coreDeps.put(new AppArtifactKey(d.getArtifact().getGroupId(),
                d.getArtifact().getArtifactId(), d.getArtifact().getClassifier(), d.getArtifact().getExtension()), d));

        MavenArtifactResolver mvn;
        try {
            mvn = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(repos)
                    .setRemoteRepositoryManager(remoteRepoManager)
                    .setWorkspaceDiscovery(false)
                    .build();
        } catch (BootstrapMavenException e) {
            throw new MojoExecutionException("Failed to initialize maven resolver", e);
        }

        final QuarkusPlatformDescriptor platformDescriptor = QuarkusJsonPlatformDescriptorResolver.newInstance()
                .setArtifactResolver(new BootstrapAppModelResolver(mvn))
                .resolveFromJson(descriptorPath);

        for (Extension ext : platformDescriptor.getExtensions()) {
            final Artifact extBom = new DefaultArtifact(ext.getGroupId(), ext.getArtifactId() + "-deployment", "bom", "xml",
                    ext.getVersion());
            final Model model;
            try {
                model = ModelUtils.readModel(mvn.resolve(extBom).getArtifact().getFile().toPath());
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to resolve extension BOM " + extBom, e);
            }
            final List<org.apache.maven.model.Dependency> managedDeps = model.getDependencyManagement() == null
                    ? Collections.emptyList()
                    : model.getDependencyManagement().getDependencies();
            if (managedDeps.isEmpty()) {
                continue;
            }
            managedDeps.forEach(d -> coreDeps
                    .remove(new AppArtifactKey(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType())));
        }

        final Model model = initModel();
        final Map<String, org.apache.maven.model.Dependency> modelDeps = new TreeMap<>();
        coreDeps.entrySet().forEach(e -> {
            final Dependency d = e.getValue();
            final Artifact a = d.getArtifact();
            final org.apache.maven.model.Dependency dep = new org.apache.maven.model.Dependency();
            dep.setGroupId(a.getGroupId());
            dep.setArtifactId(a.getArtifactId());
            dep.setVersion(a.getVersion());
            if (a.getClassifier() != null && !a.getClassifier().isEmpty()) {
                dep.setClassifier(a.getClassifier());
            }
            if (!a.getExtension().isEmpty() && !a.getExtension().equals("jar")) {
                dep.setType(a.getExtension());
            }
            if (!d.getScope().isEmpty() && !d.getScope().equals("compile")) {
                dep.setScope(d.getScope());
            }
            if (d.getOptional() != null && d.getOptional()) {
                dep.setOptional(true);
            }
            modelDeps.put(e.getKey().toString(), dep);
        });

        modelDeps.values().forEach(d -> model.addDependency(d));

        Path bom = Paths.get(project.getBuild().getDirectory()).resolve("core-bom.pom");
        try {
            Files.createDirectories(bom.getParent());
            ModelUtils.persistModel(bom, model);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to persist the model to " + bom, e);
        }

        project.setFile(bom.toFile());
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

}
