package io.quarkus.devtools.project.configuration.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.project.configuration.ConfiguredArtifact;
import io.quarkus.devtools.project.configuration.ConfiguredBom;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;
import io.quarkus.devtools.project.configuration.ValueSource;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.util.PlatformArtifacts;

class ProjectModuleContainer extends AbstractModuleContainer {

    private static final Logger log = Logger.getLogger(ProjectModuleContainer.class);

    private final LocalProject module;
    private final MavenConfiguredApplicationResolver appConfigResolver;
    private List<Profile> activePomProfiles;
    private List<Dependency> rawManagedDeps;
    private Properties rawProperties;

    private Boolean isQuarkusApp;

    ProjectModuleContainer(LocalProject module, MavenConfiguredApplicationResolver appConfigResolver) {
        super(WorkspaceModuleId.of(module.getGroupId(), module.getArtifactId(), module.getVersion()));
        this.module = module;
        this.appConfigResolver = appConfigResolver;
    }

    @Override
    public boolean isProjectModule() {
        return true;
    }

    @Override
    public ValueSource getValueSource() {
        return ValueSource.local(id, getPomFile());
    }

    @Override
    public ValueSource getValueSource(String propertyName) {
        return ValueSource.local(id, propertyName, getPomFile());
    }

    @Override
    public ResolvedValue doResolvePropertyValue(String propName, String expr) {
        var value = getRawProperties().getProperty(propName);
        if (value == null) {
            var parentModule = getParentModuleContainer();
            if (parentModule == null) {
                log.warn("Failed to locate " + propName + " in " + id);
                return ResolvedValue.of(expr, getValueSource());
            }
            return parentModule.resolvePropertyValue(expr);
        }
        if (ConfiguredValue.isPropertyExpression(value)) {
            return doResolvePropertyValue(ConfiguredValue.getPropertyName(value), value);
        }
        return ResolvedValue.of(value, getValueSource(propName));
    }

    private ModuleContainer getParentModuleContainer() {
        var parentPom = module.getRawModel().getParent();
        return parentPom == null ? null
                : appConfigResolver.resolveModuleContainer(
                        WorkspaceModuleId.of(parentPom.getGroupId(), parentPom.getArtifactId(), parentPom.getVersion()));
    }

    @Override
    protected Model resolveEffectiveModel() {
        return appConfigResolver.resolveEffectiveModel(module);
    }

    boolean isQuarkusApplication() {
        return isQuarkusApp == null ? isQuarkusApp = isQuarkusApplication(getEffectiveModel()) : isQuarkusApp;
    }

    void collectApplicationConfiguration() {
        if (!isQuarkusApplication()) {
            return;
        }
        System.out.println("Quarkus application " + id);
        System.out.println("  Platform BOMs:");
        var configuredPlatforms = getConfiguredPlatforms(getEffectiveModel());
        for (var p : configuredPlatforms) {
            var configuredBom = locateBomImport(p, false);
            System.out.println("  - " + configuredBom);
        }
    }

    private ConfiguredBom locateBomImport(ArtifactCoords platformBom, boolean inParent) {
        ModuleContainer nextSource = null;
        for (var d : getRawManagedDeps()) {
            if ("import".equals(d.getScope())) {
                var groupId = resolveValue(d.getGroupId());
                var artifactId = resolveValue(d.getArtifactId());
                var version = resolveValue(d.getVersion());
                if (platformBom.getArtifactId().equals(artifactId.getValue()) &&
                        platformBom.getGroupId().equals(groupId.getValue()) &&
                        platformBom.getVersion().equals(version.getValue())) {
                    var bomArtifact = ConfiguredArtifact.pom(ConfiguredValue.of(d.getGroupId(), groupId),
                            ConfiguredValue.of(d.getArtifactId(), artifactId),
                            ConfiguredValue.of(d.getVersion(), version), getPomFile(),
                            appConfigResolver.isProjectModule(platformBom));
                    return inParent ? ConfiguredBom.enforced(bomArtifact) : ConfiguredBom.imported(bomArtifact);
                }
                var bomModule = appConfigResolver.resolveModuleContainer(
                        WorkspaceModuleId.of(groupId.getValue(), artifactId.getValue(), version.getValue()));
                if (bomModule.isPlatformBomEnforced(platformBom)) {
                    nextSource = bomModule;
                    break;
                }
            }
        }
        if (nextSource == null) {
            nextSource = getParentModuleContainer();
            if (nextSource != null && !nextSource.isPlatformBomEnforced(platformBom)) {
                nextSource = null;
            }
        }
        if (nextSource != null) {
            if (nextSource.isProjectModule()) {
                return ((ProjectModuleContainer) nextSource).locateBomImport(platformBom, true);
            }
            return ConfiguredBom.enforced(
                    ConfiguredValue.of(platformBom.getGroupId(), nextSource.resolveValue(platformBom.getGroupId())),
                    ConfiguredValue.of(platformBom.getArtifactId(), nextSource.resolveValue(platformBom.getArtifactId())),
                    ConfiguredValue.of(platformBom.getVersion(), nextSource.resolveValue(platformBom.getVersion())), null,
                    false);
        }
        throw new RuntimeException("Failed to locate the source of " + platformBom.toCompactCoords() + " import");
    }

    private Properties getRawProperties() {
        if (rawProperties == null) {
            final List<Profile> profiles = getActivePomProfiles();
            if (profiles.isEmpty()) {
                rawProperties = module.getRawModel().getProperties();
            } else {
                rawProperties = new Properties();
                rawProperties.putAll(module.getRawModel().getProperties());
                for (Profile p : profiles) {
                    rawProperties.putAll(p.getProperties());
                }
            }
        }
        return rawProperties;
    }

    private List<Dependency> getRawManagedDeps() {
        if (rawManagedDeps == null) {
            final List<Profile> profiles = getActivePomProfiles();
            if (profiles.isEmpty()) {
                rawManagedDeps = module.getRawModel().getDependencyManagement() == null ? List.of()
                        : module.getRawModel().getDependencyManagement().getDependencies();
            } else {
                rawManagedDeps = new ArrayList<>();
                if (module.getRawModel().getDependencyManagement() != null) {
                    rawManagedDeps.addAll(module.getRawModel().getDependencyManagement().getDependencies());
                }
                for (Profile p : profiles) {
                    if (p.getDependencyManagement() != null) {
                        rawManagedDeps.addAll(p.getDependencyManagement().getDependencies());
                    }
                }
            }
        }
        return rawManagedDeps;
    }

    private List<Profile> getActivePomProfiles() {
        if (activePomProfiles == null) {
            if (module.getModelBuildingResult() != null) {
                var effectiveProfiles = module.getModelBuildingResult().getActivePomProfiles(getId().toString());
                if (effectiveProfiles.isEmpty()) {
                    activePomProfiles = List.of();
                } else {
                    final Model model = module.getRawModel();
                    var activeIds = effectiveProfiles.stream().map(Profile::getId).toList();
                    activePomProfiles = new ArrayList<>(effectiveProfiles.size());
                    for (Profile p : model.getProfiles()) {
                        if (activeIds.contains(p.getId())) {
                            activePomProfiles.add(p);
                        }
                    }
                }
            } else {
                activePomProfiles = List.of();
            }
        }
        return activePomProfiles;
    }

    @Override
    protected Path getPomFile() {
        return module.getRawModel().getPomFile().toPath();
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
