package io.quarkus.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.utilities.MojoUtils;

@Mojo(name = "generate-platform-bom")
public class GeneratePlatformPomMojo extends AbstractMojo {

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repos;

    @Component
    private MavenProject project;
    @Component
    private MavenProjectHelper projectHelper;

    private MavenArtifactResolver resolver;

    private List<org.eclipse.aether.graph.Dependency> allManagedDeps = new ArrayList<>();
    private Set<AppArtifactKey> allManagedDepKeys = new HashSet<>();
    private Set<AppArtifactKey> addedExtensions = new HashSet<>();
    private Map<Artifact, Set<AppArtifactKey>> quarkusCoreExtensionKeys = new HashMap<>();

    private Artifact targetQuarkusBom;

    public GeneratePlatformPomMojo() {
        MojoLogger.logSupplier = this::getLog;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final long startTime = System.currentTimeMillis();

        try {
            resolver = MavenArtifactResolver.builder().setRepositorySystem(repoSystem).setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(repos).build();
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to initialize Maven artifact resolver", e);
        }

        Model originalModel = getRawModel();

        final DependencyManagement dm = originalModel.getDependencyManagement();
        final List<Dependency> managedDeps = dm.getDependencies();
        if (managedDeps.isEmpty()) {
            throw new MojoExecutionException("The dependency management of " + project.getArtifact() + " appears to be empty");
        }

        final List<Dependency> directDeps = new ArrayList<>();
        for (Dependency dep : managedDeps) {
            if ("import".equals(dep.getScope())) {
                if ("quarkus-bom".equals(dep.getArtifactId())
                        && "io.quarkus".equals(dep.getGroupId())) {
                    targetQuarkusBom = new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), "", "pom",
                            resolveVersion(dep.getVersion()));
                } else {
                    dep.setVersion(resolveVersion(dep.getVersion()));
                    directDeps.add(dep);
                    getLog().info("BOM import " + dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion());
                }
            } else {
                dep.setVersion(resolveVersion(dep.getVersion()));
                directDeps.add(dep);
                getLog().info("Direct dependency " + dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion());
            }
        }

        if (targetQuarkusBom == null) {
            final Artifact quarkusCore = locateQuarkusCore(resolveArtifactDescriptor(
                    new DefaultArtifact(project.getGroupId(), project.getArtifactId(), "", "pom", project.getVersion())));
            if (quarkusCore == null) {
                throw new MojoExecutionException("Failed to locate io.quarkus:quarkus-bom import in " + project.getFile());
            }
            targetQuarkusBom = new DefaultArtifact(quarkusCore.getGroupId(), "quarkus-bom", "", "pom",
                    quarkusCore.getVersion());
        }
        final ArtifactDescriptorResult quarkusBomDescr = resolveArtifactDescriptor(targetQuarkusBom);
        addedExtensions = getExtensionKeys(quarkusBomDescr.getManagedDependencies());
        int i = 1;
        for (org.eclipse.aether.graph.Dependency managedDep : quarkusBomDescr.getManagedDependencies()) {
            if (!isAcceptableBomDependency(managedDep.getArtifact())) {
                System.out.println(i++ + ") NOT ACCEPTABLE " + managedDep.getArtifact());
            }
            if (allManagedDepKeys.add(getKey(managedDep.getArtifact()))) {
                allManagedDeps.add(managedDep);
            }
        }

        for (Dependency bomImport : directDeps) {
            processDirectDep(bomImport);
        }

        final Model resultingModel = initNewModel();
        final DependencyManagement newDm = new DependencyManagement();
        resultingModel.setDependencyManagement(newDm);
        for (org.eclipse.aether.graph.Dependency dep : allManagedDeps) {
            newDm.addDependency(toModelDep(dep));
        }

        final File targetFile = new File(project.getBasedir(), ".quarkus-platform-bom.xml");
        try {
            MojoUtils.write(resultingModel, targetFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to persist the generated model at " + targetFile);
        }

        final long timeMs = System.currentTimeMillis() - startTime;
        final long timeSec = timeMs / 1000;
        getLog().info("BOM generation took " + timeSec + "." + (timeMs - timeSec * 1000) + " sec");

        //project.setPomFile(targetFile);
    }

    private Dependency toModelDep(org.eclipse.aether.graph.Dependency managedDep) {
        final Dependency dep = new Dependency();
        final Artifact artifact = managedDep.getArtifact();
        dep.setGroupId(artifact.getGroupId());
        dep.setArtifactId(artifact.getArtifactId());
        if (!artifact.getClassifier().isEmpty()) {
            dep.setClassifier(artifact.getClassifier());
        }
        if (!artifact.getExtension().isEmpty() && !artifact.getExtension().equals("jar")) {
            dep.setType(artifact.getExtension());
        }
        dep.setVersion(artifact.getVersion());
        if (!managedDep.getScope().isEmpty() && !"compile".equals(managedDep.getScope())) {
            dep.setScope(managedDep.getScope());
        }
        if (managedDep.getOptional() != null && managedDep.getOptional()) {
            dep.setOptional(managedDep.getOptional());
        }
        if (!managedDep.getExclusions().isEmpty()) {
            for (org.eclipse.aether.graph.Exclusion excl : managedDep.getExclusions()) {
                final Exclusion e = new Exclusion();
                e.setGroupId(excl.getGroupId());
                e.setArtifactId(excl.getArtifactId());
                dep.addExclusion(e);
            }
        }
        return dep;
    }

    private org.eclipse.aether.graph.Dependency toAetherDep(Dependency managedDep) {
        final Artifact artifact = new DefaultArtifact(managedDep.getGroupId(), managedDep.getArtifactId(),
                managedDep.getClassifier(), managedDep.getType(), managedDep.getVersion());
        List<org.eclipse.aether.graph.Exclusion> exclusions = Collections.emptyList();
        if (!managedDep.getExclusions().isEmpty()) {
            exclusions = new ArrayList<>();
            for (Exclusion excl : managedDep.getExclusions()) {
                exclusions.add(new org.eclipse.aether.graph.Exclusion(excl.getGroupId(), excl.getArtifactId(), "", null));
            }
        }
        return new org.eclipse.aether.graph.Dependency(artifact, managedDep.getScope(),
                managedDep.getOptional() == null ? null : Boolean.valueOf(managedDep.getOptional()), exclusions);
    }

    private void processDirectDep(Dependency directDep) throws MojoExecutionException {
        final Artifact artifact = new DefaultArtifact(directDep.getGroupId(), directDep.getArtifactId(),
                directDep.getClassifier(), directDep.getType(),
                directDep.getVersion());
        if ("import".equals(directDep.getScope())) {
            // BOM import
            final ArtifactDescriptorResult bomDescr = resolveArtifactDescriptor(artifact);
            final Artifact quarkusCore = locateQuarkusCore(bomDescr);
            Set<AppArtifactKey> quarkusImportedKeys = Collections.emptySet();
            if (quarkusCore != null && !quarkusCore.equals(targetQuarkusBom)) {
                quarkusImportedKeys = getQuarkusCoreExtensionKeys(quarkusCore);
            }
            final Set<AppArtifactKey> bomManagedDepKeys = new HashSet<>();
            for (org.eclipse.aether.graph.Dependency managedDep : bomDescr.getManagedDependencies()) {
                bomManagedDepKeys.add(getKey(managedDep.getArtifact()));
            }
            for (org.eclipse.aether.graph.Dependency dep : bomDescr.getManagedDependencies()) {
                final AppArtifactKey key = getKey(dep.getArtifact());
                if (addedExtensions.contains(key)
                        || quarkusImportedKeys.contains(key)
                        || !isQuarkusExtension(dep.getArtifact())) {
                    continue;
                }
                addedExtensions.add(key);
                allManagedDeps.add(dep);
                allManagedDepKeys.add(key);
                addDependencies(dep, bomManagedDepKeys);
            }
            return;
        }

        final AppArtifactKey key = new AppArtifactKey(directDep.getGroupId(), directDep.getArtifactId(),
                directDep.getClassifier(), directDep.getType());
        if (!allManagedDepKeys.add(key)) {
            return;
        }
        if (isQuarkusExtension(artifact)) {
            addedExtensions.add(key);
        }
        org.eclipse.aether.graph.Dependency dep = toAetherDep(directDep);
        allManagedDeps.add(dep);
        addDependencies(dep, Collections.emptySet());
    }

    private void addDependencies(org.eclipse.aether.graph.Dependency dep, Set<AppArtifactKey> bomDeps)
            throws MojoExecutionException {
        final CollectResult collectResult;
        try {
            collectResult = resolver.collectManagedDependencies(dep.getArtifact(), Collections.emptyList(), allManagedDeps,
                    Collections.emptyList(), "provided", "test");
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to collect dependencies for " + dep.getArtifact(), e);
        }
        addDependencies(collectResult.getRoot().getChildren(), bomDeps);
    }

    private void addDependencies(List<DependencyNode> list, Set<AppArtifactKey> bomDeps) {
        if (list.isEmpty()) {
            return;
        }
        int i = 0;
        while (i < list.size()) {
            addDependencies(list.get(i++), bomDeps);
        }
    }

    private void addDependencies(DependencyNode node, Set<AppArtifactKey> bomDeps) {
        if (!isAcceptableBomDependency(node.getArtifact())) {
            return;
        }
        final AppArtifactKey key = getKey(node.getArtifact());
        if (!bomDeps.contains(key) || !allManagedDepKeys.add(key)) {
            return;
        }
        if (node.getArtifact().getClassifier().equals("tests")) {
            throw new IllegalStateException(node.getArtifact().toString());
        }
        allManagedDeps.add(node.getDependency());
        addDependencies(node.getChildren(), bomDeps);
    }

    private Artifact locateQuarkusCore(final ArtifactDescriptorResult bomDescr) {
        for (org.eclipse.aether.graph.Dependency dep : bomDescr.getManagedDependencies()) {
            if (dep.getArtifact().getArtifactId().equals("quarkus-core")
                    && dep.getArtifact().getGroupId().equals("io.quarkus")) {
                return dep.getArtifact();
            }
        }
        return null;
    }

    private ArtifactDescriptorResult resolveArtifactDescriptor(final Artifact bomArtifact) throws MojoExecutionException {
        try {
            return resolver.resolveDescriptor(bomArtifact);
        } catch (AppModelResolverException e) {
            throw new MojoExecutionException("Failed to resolve descriptor for " + bomArtifact);
        }
    }

    private String resolveVersion(String version) throws MojoExecutionException {
        if (version.charAt(0) != '$') {
            return version;
        }
        final String prop = version.substring(2, version.length() - 1);
        Object value = project.getProperties().get(prop);
        if (value == null) {
            value = System.getProperty(prop);
        }
        if (value == null) {
            throw new MojoExecutionException("Failed to resolve version " + version);
        }
        return value.toString();
    }

    private Model initNewModel() {
        final Model originalModel = project.getModel();
        final Model resultingModel = new Model();
        resultingModel.setModelVersion(originalModel.getModelVersion());
        resultingModel.setGroupId(originalModel.getGroupId());
        resultingModel.setArtifactId(originalModel.getArtifactId());
        resultingModel.setVersion(originalModel.getVersion());
        resultingModel.setPackaging(originalModel.getPackaging());
        resultingModel.setName(originalModel.getName());
        resultingModel.setDescription(originalModel.getDescription());
        resultingModel.setUrl(originalModel.getUrl());
        resultingModel.setLicenses(originalModel.getLicenses());
        resultingModel.setDevelopers(originalModel.getDevelopers());
        resultingModel.setScm(originalModel.getScm());
        return resultingModel;
    }

    private Model getRawModel() throws MojoExecutionException {
        Model originalModel;
        try {
            originalModel = MojoUtils.readPom(project.getFile());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to parse " + project.getFile(), e);
        }
        return originalModel;
    }

    private Set<AppArtifactKey> getQuarkusCoreExtensionKeys(Artifact quarkusCore) throws MojoExecutionException {
        Set<AppArtifactKey> keys = quarkusCoreExtensionKeys.get(quarkusCore);
        if (keys != null) {
            return keys;
        }
        keys = getExtensionKeys(resolveArtifactDescriptor(quarkusCore).getManagedDependencies());
        quarkusCoreExtensionKeys.put(quarkusCore, keys);
        return keys;
    }

    private Set<AppArtifactKey> getExtensionKeys(List<org.eclipse.aether.graph.Dependency> deps) throws MojoExecutionException {
        final Set<AppArtifactKey> keys = new HashSet<>();
        for (org.eclipse.aether.graph.Dependency dep : deps) {
            final Artifact artifact = dep.getArtifact();
            if (isQuarkusExtension(artifact)) {
                keys.add(getKey(artifact));
            }
        }
        return keys;
    }

    private static boolean isAcceptableBomDependency(Artifact artifact) {
        return !"javadoc".equals(artifact.getClassifier())
                && !"tests".equals(artifact.getClassifier())
                && !"sources".equals(artifact.getClassifier());
    }

    private static boolean isPossiblyExtension(Artifact artifact) {
        return artifact.getExtension().equals("jar") && isAcceptableBomDependency(artifact);
    }

    private AppArtifactKey getKey(final Artifact artifact) {
        return new AppArtifactKey(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(),
                artifact.getExtension());
    }

    private boolean isQuarkusExtension(Artifact artifact) throws MojoExecutionException {
        if (!isPossiblyExtension(artifact)) {
            return false;
        }
        if (addedExtensions.contains(getKey(artifact))) {
            return true;
        }
        try {
            return isQuarkusExtension(resolver.resolve(artifact).getArtifact().getFile().toPath());
        } catch (AppModelResolverException e) {
            getLog().warn("Failed to resolve " + artifact);
        }
        return false;
    }

    private static boolean isQuarkusExtension(Path p) throws MojoExecutionException {
        if (Files.isDirectory(p)) {
            return isQuarkusExtensionDir(p);
        } else {
            try (FileSystem fs = FileSystems.newFileSystem(p, (ClassLoader) null)) {
                return isQuarkusExtensionDir(fs.getPath("/"));
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to open " + p, e);
            }
        }
    }

    private static boolean isQuarkusExtensionDir(Path dir) throws MojoExecutionException {
        return Files.exists(dir.resolve(BootstrapConstants.DESCRIPTOR_PATH));
    }
}
