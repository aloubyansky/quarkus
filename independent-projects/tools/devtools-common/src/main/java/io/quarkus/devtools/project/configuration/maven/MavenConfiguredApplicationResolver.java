package io.quarkus.devtools.project.configuration.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.EffectiveModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.maven.dependency.ArtifactCoords;

public class MavenConfiguredApplicationResolver {

    public static Collection<ConfiguredApplication> load(Path projectDir) throws Exception {
        return load(projectDir, MessageWriter.info());
    }

    public static Collection<ConfiguredApplication> load(Path projectDir, MessageWriter log) throws Exception {
        return load(projectDir, getBootstrapMavenContext(projectDir),
                log);
    }

    public static BootstrapMavenContext getBootstrapMavenContext(Path projectDir) throws BootstrapMavenException {
        return new BootstrapMavenContext(
                BootstrapMavenContext.config()
                        .setCurrentProject(projectDir.toString())
                        .setEffectiveModelBuilder(true)
                        .setPreferPomsFromWorkspace(true));
    }

    public static Collection<ConfiguredApplication> load(Path projectDir, BootstrapMavenContext mavenCtx) throws Exception {
        return load(projectDir, mavenCtx, MessageWriter.info());
    }

    public static Collection<ConfiguredApplication> load(Path projectDir, BootstrapMavenContext mavenCtx, MessageWriter log)
            throws Exception {
        return new MavenConfiguredApplicationResolver(mavenCtx, log).loadInternal(projectDir);
    }

    private final BootstrapMavenContext mavenContext;
    private final MessageWriter log;
    private final Map<WorkspaceModuleId, ModuleContainer> modules = new HashMap<>();
    private EffectiveModelResolver effectiveModelResolver;

    private MavenConfiguredApplicationResolver(BootstrapMavenContext mavenContext, MessageWriter log) {
        this.mavenContext = mavenContext;
        this.log = log;
    }

    private Collection<ConfiguredApplication> loadInternal(Path projectDir) {
        final LocalWorkspace workspace = mavenContext.getWorkspace();
        if (workspace == null) {
            throw new RuntimeException("Failed to load workspace from " + projectDir);
        }
        final Collection<LocalProject> modules = workspace.getProjects().values();
        final List<Path> createdDirs = ensureResolvable(modules);
        final List<ConfiguredApplication> result = new ArrayList<>(modules.size() < 2 ? modules.size() : 10);
        try {
            for (var module : modules) {
                if (module.getDir().startsWith(projectDir)) {
                    var mc = new ProjectModuleContainer(module, this);
                    this.modules.put(mc.getId(), mc);
                    if (mc.isQuarkusApplication()) {
                        result.add(mc.readApplicationConfiguration());
                    }
                }
            }
        } finally {
            for (Path p : createdDirs) {
                IoUtils.recursiveDelete(p);
            }
        }
        return result;
    }

    ModuleContainer resolveModuleContainer(WorkspaceModuleId id) {
        return modules.computeIfAbsent(id, this::newModuleContainer);
    }

    boolean isProjectModule(ArtifactCoords coords) {
        return isProjectModule(coords.getGroupId(), coords.getArtifactId());
    }

    boolean isProjectModule(Artifact artifact) {
        return isProjectModule(artifact.getGroupId(), artifact.getArtifactId());
    }

    boolean isProjectModule(String groupId, String artifactId) {
        return mavenContext.getWorkspace().getProject(groupId, artifactId) != null;
    }

    ProjectModuleContainer getLocalModuleOrNull(String groupId, String artifactId, String version) {
        var localModule = mavenContext.getWorkspace().getProject(groupId, artifactId);
        if (localModule == null) {
            return null;
        }
        return (ProjectModuleContainer) modules.computeIfAbsent(WorkspaceModuleId.of(groupId, artifactId, version),
                k -> new ProjectModuleContainer(localModule, this));
    }

    private ModuleContainer newModuleContainer(WorkspaceModuleId gav) {
        var localModule = mavenContext.getWorkspace().getProject(gav.getGroupId(), gav.getArtifactId());
        return localModule == null
                ? new ExternalModuleContainer(gav,
                        doResolveEffectiveModel(ArtifactCoords.pom(gav.getGroupId(), gav.getArtifactId(), gav.getVersion())))
                : new ProjectModuleContainer(localModule, this);
    }

    Model resolveEffectiveModel(LocalProject project) {
        return project.getModelBuildingResult() == null ? doResolveEffectiveModel(getPomCoords(project))
                : project.getModelBuildingResult().getEffectiveModel();
    }

    List<DependencyNode> collectDependencies(ProjectModuleContainer module) {
        var effectiveModel = module.getEffectiveModel();
        var moduleId = module.getId();
        try {
            return mavenContext.getRepositorySystem().collectDependencies(mavenContext.getRepositorySystemSession(),
                    new CollectRequest()
                            .setRootArtifact(new DefaultArtifact(moduleId.getGroupId(), moduleId.getArtifactId(),
                                    ArtifactCoords.TYPE_POM, moduleId.getVersion()))
                            .setDependencies(toAetherDeps(effectiveModel.getDependencies()))
                            .setManagedDependencies(effectiveModel.getDependencyManagement() == null ? List.of()
                                    : toAetherDeps(effectiveModel.getDependencyManagement().getDependencies()))
                            .setRepositories(module.getEffectiveRepositories()))
                    .getRoot().getChildren();
        } catch (Exception e) {
            throw new RuntimeException("Failed to collect dependency information for " + moduleId, e);
        }
    }

    ArtifactDescriptorResult resolveDescriptor(ArtifactCoords coords, ProjectModuleContainer module) {
        try {
            return mavenContext.getRepositorySystem().readArtifactDescriptor(mavenContext.getRepositorySystemSession(),
                    new ArtifactDescriptorRequest()
                            .setArtifact(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(),
                                    ArtifactCoords.TYPE_POM, coords.getVersion()))
                            .setRepositories(module.getEffectiveRepositories()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve module descriptor for " + module.getId(), e);
        }
    }

    Artifact resolveArtifact(DependencyNode node) {
        try {
            return mavenContext.getRepositorySystem().resolveArtifact(mavenContext.getRepositorySystemSession(),
                    new ArtifactRequest(node)).getArtifact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve artifact " + node.getArtifact(), e);
        }
    }

    private static List<Dependency> toAetherDeps(List<org.apache.maven.model.Dependency> deps) {
        final List<Dependency> result = new ArrayList<>(deps.size());
        for (org.apache.maven.model.Dependency d : deps) {
            result.add(new Dependency(
                    new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType(), d.getVersion()),
                    d.getScope()));
        }
        return result;
    }

    private Model doResolveEffectiveModel(ArtifactCoords pom) {
        if (effectiveModelResolver == null) {
            try {
                effectiveModelResolver = EffectiveModelResolver.of(new MavenArtifactResolver(mavenContext));
            } catch (BootstrapMavenException e) {
                throw new RuntimeException("Failed to initialize Maven artifact resolver", e);
            }
        }
        return effectiveModelResolver.resolveEffectiveModel(pom);
    }

    private static ArtifactCoords getPomCoords(LocalProject project) {
        return ArtifactCoords.pom(project.getGroupId(), project.getArtifactId(), project.getVersion());
    }

    private static List<Path> ensureResolvable(Collection<LocalProject> modules) {
        final List<Path> createdDirs = new ArrayList<>();
        for (var project : modules) {
            ensureResolvable(project, createdDirs);
        }
        return createdDirs;
    }

    private static void ensureResolvable(LocalProject project, List<Path> createdDirs) {
        if (!project.getRawModel().getPackaging().equals(ArtifactCoords.TYPE_POM)) {
            ensureDirExists(createdDirs, project.getClassesDir());
            ensureDirExists(createdDirs, project.getTestClassesDir());
        }
    }

    private static void ensureDirExists(List<Path> createdDirs, Path classesDir) {
        if (!Files.exists(classesDir)) {
            Path topDirToCreate = classesDir;
            while (!Files.exists(topDirToCreate.getParent())) {
                topDirToCreate = topDirToCreate.getParent();
            }
            try {
                Files.createDirectories(classesDir);
                createdDirs.add(topDirToCreate);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create " + classesDir, e);
            }
        }
    }
}
