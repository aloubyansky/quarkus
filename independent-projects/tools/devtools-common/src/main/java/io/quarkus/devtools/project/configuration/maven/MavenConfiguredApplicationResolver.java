package io.quarkus.devtools.project.configuration.maven;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.model.Model;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.EffectiveModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredProject;
import io.quarkus.maven.dependency.ArtifactCoords;

public class MavenConfiguredApplicationResolver {

    public static ConfiguredProject load(Path projectDir) throws Exception {
        return load(projectDir, MessageWriter.info());
    }

    public static ConfiguredProject load(Path projectDir, MessageWriter log) throws Exception {
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

    public static ConfiguredProject load(Path projectDir, BootstrapMavenContext mavenCtx) throws Exception {
        return load(projectDir, mavenCtx, MessageWriter.info());
    }

    public static ConfiguredProject load(Path projectDir, BootstrapMavenContext mavenCtx, MessageWriter log) throws Exception {
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

    private ConfiguredProject loadInternal(Path projectDir) {
        for (var module : mavenContext.getWorkspace().getProjects().values()) {
            if (module.getDir().startsWith(projectDir)) {
                var mc = new ProjectModuleContainer(module, this);
                modules.put(mc.getId(), mc);
                mc.collectApplicationConfiguration();
            }
        }
        return null;
    }

    ModuleContainer resolveModuleContainer(WorkspaceModuleId id) {
        return modules.computeIfAbsent(id, this::newModuleContainer);
    }

    boolean isProjectModule(ArtifactCoords coords) {
        return mavenContext.getWorkspace().getProject(coords.getGroupId(), coords.getArtifactId()) != null;
    }

    private ModuleContainer newModuleContainer(WorkspaceModuleId gav) {
        var localModule = mavenContext.getWorkspace().getProject(gav.getGroupId(), gav.getArtifactId());
        if (gav.getGroupId().equals("org.apache.camel.quarkus") && localModule == null) {
            System.out.println("Failed to locate " + gav);
        }
        return localModule == null
                ? new ExternalModuleContainer(gav,
                        doResolveEffectiveModel(ArtifactCoords.pom(gav.getGroupId(), gav.getArtifactId(), gav.getVersion())))
                : new ProjectModuleContainer(localModule, this);
    }

    Model resolveEffectiveModel(LocalProject project) {
        return project.getModelBuildingResult() == null ? doResolveEffectiveModel(getPomCoords(project))
                : project.getModelBuildingResult().getEffectiveModel();
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
}
