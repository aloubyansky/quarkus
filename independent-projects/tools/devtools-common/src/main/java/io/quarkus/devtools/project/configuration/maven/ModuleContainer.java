package io.quarkus.devtools.project.configuration.maven;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;

import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.util.PlatformArtifacts;

class ModuleContainer {

    private final LocalProject module;
    private final WorkspaceModuleId id;
    private final MavenConfiguredApplicationResolver appResolver;
    private Model effectiveModel;

    private Boolean isQuarkusApp;

    ModuleContainer(LocalProject module, MavenConfiguredApplicationResolver appResolver) {
        this.module = module;
        id = WorkspaceModuleId.of(module.getGroupId(), module.getArtifactId(), module.getVersion());
        this.appResolver = appResolver;
    }

    WorkspaceModuleId getId() {
        return id;
    }

    boolean isQuarkusApplication() {
        return isQuarkusApp == null ? isQuarkusApp = isQuarkusApplication(getEffectiveModel()) : isQuarkusApp;
    }

    void collectApplicationConfiguration() {
        if (!isQuarkusApplication()) {
            return;
        }

        var configuredPlatforms = getConfiguredPlatforms(getEffectiveModel());
        for (var p : configuredPlatforms) {
            System.out.println("platform: " + p.toCompactCoords());
        }
    }

    private Model getEffectiveModel() {
        return effectiveModel == null ? appResolver.resolveEffectiveModel(module) : effectiveModel;
    }

    private static List<ArtifactCoords> getConfiguredPlatforms(Model effectiveModel) {
        var dm = effectiveModel.getDependencyManagement();
        if (dm == null) {
            return List.of();
        }
        final List<ArtifactCoords> platformBoms = new ArrayList<>(4);
        for (Dependency d : dm.getDependencies()) {
            if (d.getType().equals("json")
                    && PlatformArtifacts.isCatalogArtifactId(d.getArtifactId())
                    && d.getClassifier().equals(d.getVersion())) {
                platformBoms.add(ArtifactCoords.pom(d.getGroupId(),
                        PlatformArtifacts.ensureBomArtifactId(d.getArtifactId()), d.getVersion()));
            }
        }
        return platformBoms;
    }

    private static boolean isQuarkusApplication(Model effectiveModel) {
        if (ArtifactCoords.TYPE_POM.equals(effectiveModel.getPackaging())) {
            return false;
        }
        var build = effectiveModel.getBuild();
        if (build != null) {
            for (var plugin : build.getPlugins()) {
                if (isQuarkusPluginWithBuildGoal(plugin)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isQuarkusPluginWithBuildGoal(Plugin plugin) {
        if ("quarkus-maven-plugin".equals(plugin.getArtifactId())) {
            for (var e : plugin.getExecutions()) {
                for (var goal : e.getGoals()) {
                    if ("build".equals(goal)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
