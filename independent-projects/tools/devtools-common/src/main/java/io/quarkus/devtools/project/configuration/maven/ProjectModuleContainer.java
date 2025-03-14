package io.quarkus.devtools.project.configuration.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyNode;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.resolver.maven.ModelResolutionTaskRunner;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.util.DependencyUtils;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.devtools.project.configuration.ConfiguredArtifact;
import io.quarkus.devtools.project.configuration.ConfiguredBom;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;
import io.quarkus.devtools.project.configuration.ValueSource;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.PathTree;
import io.quarkus.paths.PathVisit;
import io.quarkus.registry.util.PlatformArtifacts;

class ProjectModuleContainer extends AbstractModuleContainer {

    private static final Logger log = Logger.getLogger(ProjectModuleContainer.class);

    private final LocalProject module;
    private final MavenConfiguredApplicationResolver appConfigResolver;
    private final Map<ArtifactKey, PlatformVersionConstraint> platformBomConstraints = new HashMap<>();
    private List<Profile> activePomProfiles;
    private List<Dependency> rawManagedDeps;
    private List<Dependency> rawDirectDeps;
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

    ConfiguredApplication readApplicationConfiguration() {
        var app = new ConfiguredApplication(id, getPomFile());
        collectPlatformBoms(app);
        collectTopExtensionDeps(app);
        return app;
    }

    private void collectTopExtensionDeps(ConfiguredApplication app) {
        var taskRunner = ModelResolutionTaskRunner.getNonBlockingTaskRunner();
        var collectedDirectDeps = new ConcurrentHashMap<ArtifactKey, Artifact>();
        visitDependencies(appConfigResolver.collectDependencies(this), taskRunner, collectedDirectDeps);
        taskRunner.waitForCompletion();
        locateDependencyConfig(collectedDirectDeps, app);
        if (!collectedDirectDeps.isEmpty()) {
            throw new RuntimeException("Failed to locate configuration of dependencies " + collectedDirectDeps.values()
                    + " for " + app.getModuleConfigFile());
        }
    }

    private void visitDependencies(List<DependencyNode> nodes, ModelResolutionTaskRunner taskRunner,
            Map<ArtifactKey, Artifact> collectedDirectDeps) {
        for (var node : nodes) {
            if (isMaybeExtension(node.getArtifact())) {
                visitDependency(node, taskRunner, collectedDirectDeps);
            }
        }
    }

    private void visitDependency(DependencyNode node, ModelResolutionTaskRunner taskRunner,
            Map<ArtifactKey, Artifact> collectedDirectDeps) {
        taskRunner.run(() -> PathTree.ofDirectoryOrArchive(appConfigResolver.resolveArtifact(node).getFile().toPath())
                .accept(BootstrapConstants.DESCRIPTOR_PATH,
                        visit -> visitExtensionDependency(node, taskRunner, visit, collectedDirectDeps)));
    }

    private void visitExtensionDependency(DependencyNode node, ModelResolutionTaskRunner taskRunner, PathVisit visit,
            Map<ArtifactKey, Artifact> collectedDirectDeps) {
        // for the update use-case we are not really interested whether a module represents a Quarkus extension or not,
        // we care only about updating external dependencies
        if (appConfigResolver.isProjectModule(node.getArtifact())) {
            visitDependencies(node.getChildren(), taskRunner, collectedDirectDeps);
        } else if (visit == null) {
            var platformConstraint = platformBomConstraints.get(DependencyUtils.getKey(node.getArtifact()));
            // If a non-extension dependency is managed by a platform BOM then even if it depends on extensions,
            // theoretically, upgrading the platform BOM version should keep those transitive dependencies on extensions
            // aligned with the platform.
            // Here we could check whether these dependencies have been misaligned with the platform BOM.
            if (platformConstraint == null) {
                visitDependencies(node.getChildren(), taskRunner, collectedDirectDeps);
            }
        } else {
            collectedDirectDeps.put(DependencyUtils.getKey(node.getArtifact()), node.getArtifact());
        }
    }

    private void locateDependencyConfig(Map<ArtifactKey, Artifact> extensions, ConfiguredApplication app) {
        final List<Dependency> directDeps = getRawDirectDeps();
        List<ProjectModuleContainer> localModuleDeps = null;
        for (int i = 0; i < directDeps.size(); ++i) {
            var directDep = directDeps.get(i);
            var groupId = resolveValue(directDep.getGroupId());
            var artifactId = resolveValue(directDep.getArtifactId());
            var classifier = resolveValue(directDep.getClassifier());
            var type = resolveValue(directDep.getType());
            var extensionArtifact = extensions
                    .remove(ArtifactKey.of(groupId.getValue(), artifactId.getValue(), classifier.getValue(), type.getValue()));
            if (extensionArtifact != null) {
                var configured = ConfiguredArtifact.of(ConfiguredValue.of(directDep.getGroupId(), groupId),
                        ConfiguredValue.of(directDep.getArtifactId(), artifactId),
                        ConfiguredValue.of(directDep.getClassifier(), classifier),
                        ConfiguredValue.of(directDep.getType(), type),
                        ConfiguredValue.of(directDep.getVersion(), resolveValue(directDep.getVersion())),
                        getPomFile());
                app.addTopExtensionDependency(configured);
                if (extensions.isEmpty()) {
                    return;
                }
            } else {
                var localModuleDep = appConfigResolver.getLocalModuleOrNull(directDep.getGroupId(), directDep.getArtifactId(),
                        directDep.getVersion());
                if (localModuleDep != null) {
                    if (localModuleDeps == null) {
                        localModuleDeps = new ArrayList<>(directDeps.size() - i - 1);
                    }
                    localModuleDeps.add(localModuleDep);
                }
            }
        }

        if (localModuleDeps != null) {
            // This is not the proper way of locating dependencies. But it's a start.
            // 1. This is not how Maven selects dependencies when there are conflicts in the graph.
            // 2. This way we will not locate all the duplicate extension dependencies (if there are duplicates) in the project.
            for (var localModuleDep : localModuleDeps) {
                localModuleDep.locateDependencyConfig(extensions, app);
            }
            if (extensions.isEmpty()) {
                return;
            }
        }

        ModuleContainer parentModule = getParentModuleContainer();
        if (parentModule == null) {
            return;
        }
        if (parentModule.isProjectModule()) {
            ((ProjectModuleContainer) parentModule).locateDependencyConfig(extensions, app);
        } else {
            for (var dep : parentModule.getEffectiveModel().getDependencies()) {
                var extensionArtifact = extensions
                        .remove(ArtifactKey.of(dep.getGroupId(), dep.getArtifactId(), dep.getClassifier(), dep.getType()));
                if (extensionArtifact != null) {
                    app.addTopExtensionDependency(ConfiguredArtifact.of(ConfiguredValue.of(dep.getGroupId()),
                            ConfiguredValue.of(dep.getArtifactId()),
                            ConfiguredValue.of(dep.getClassifier()),
                            ConfiguredValue.of(dep.getType()),
                            ConfiguredValue.of(dep.getVersion()),
                            null));
                }
            }
        }
    }

    private void collectPlatformBoms(ConfiguredApplication app) {
        var configuredPlatforms = getConfiguredPlatforms(getEffectiveModel());
        for (var platformBom : configuredPlatforms) {
            var configuredBom = locateBomImport(platformBom, false);
            loadVersionConstraints(platformBom);
            app.addPlatformBom(configuredBom);
        }
    }

    private void loadVersionConstraints(ArtifactCoords bom) {
        for (var dep : appConfigResolver.resolveDescriptor(bom, this).getManagedDependencies()) {
            platformBomConstraints.computeIfAbsent(DependencyUtils.getKey(dep.getArtifact()),
                    k -> new PlatformVersionConstraint(dep.getArtifact().getVersion(), bom));
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

    private void getManagedPluginVersion(ArtifactKey pluginKey) {
        var managedPlugins = getRawManagedPlugins();

    }

    private List<Plugin> getRawManagedPlugins() {
        List<Plugin> managedPlugins = getRawManagedPlugins(module.getRawModel().getBuild());
        final List<Profile> profiles = getActivePomProfiles();
        if (profiles.isEmpty()) {
            return managedPlugins;
        }
        List<Plugin> combined = null;
        for (Profile p : profiles) {
            var profileManagedPlugins = getRawManagedPlugins(p.getBuild());
            if (!profileManagedPlugins.isEmpty()) {
                if (combined == null) {
                    combined = new ArrayList<>();
                    combined.addAll(managedPlugins);
                }
                combined.addAll(profileManagedPlugins);
            }
        }
        return combined == null ? managedPlugins : combined;
    }

    private static List<Plugin> getRawManagedPlugins(BuildBase build) {
        if (build == null) {
            return List.of();
        }
        var pm = build.getPluginManagement();
        return pm == null ? List.of() : pm.getPlugins();
    }

    private List<Dependency> getRawDirectDeps() {
        if (rawDirectDeps == null) {
            final List<Profile> profiles = getActivePomProfiles();
            if (profiles.isEmpty()) {
                rawDirectDeps = module.getRawModel().getDependencies();
            } else {
                List<Dependency> combined = null;
                for (Profile p : profiles) {
                    final List<Dependency> profileDeps = p.getDependencies();
                    if (!profileDeps.isEmpty()) {
                        if (combined == null) {
                            combined = new ArrayList<>();
                            combined.addAll(module.getRawModel().getDependencies());
                        }
                        combined.addAll(profileDeps);
                    }

                }
                rawDirectDeps = combined == null ? module.getRawModel().getDependencies() : combined;
            }
        }
        return rawDirectDeps;
    }

    private Properties getRawProperties() {
        if (rawProperties == null) {
            final List<Profile> profiles = getActivePomProfiles();
            if (profiles.isEmpty()) {
                rawProperties = module.getRawModel().getProperties();
            } else {
                Properties combined = null;
                for (Profile profile : profiles) {
                    if (!profile.getProperties().isEmpty()) {
                        if (combined == null) {
                            combined = new Properties();
                            combined.putAll(module.getRawModel().getProperties());
                        }
                        combined.putAll(profile.getProperties());
                    }
                }
                rawProperties = combined == null ? module.getRawModel().getProperties() : combined;
            }
        }
        return rawProperties;
    }

    private List<Dependency> getRawManagedDeps() {
        if (rawManagedDeps == null) {
            final List<Dependency> mainManagedDeps = module.getRawModel().getDependencyManagement() == null ? List.of()
                    : module.getRawModel().getDependencyManagement().getDependencies();
            final List<Profile> profiles = getActivePomProfiles();
            if (profiles.isEmpty()) {
                rawManagedDeps = mainManagedDeps;
            } else {
                List<Dependency> combined = null;
                for (Profile profile : profiles) {
                    final List<Dependency> profileManagedDeps = profile.getDependencyManagement() == null ? List.of()
                            : profile.getDependencyManagement().getDependencies();
                    if (!profileManagedDeps.isEmpty()) {
                        if (combined == null) {
                            combined = new ArrayList<>(mainManagedDeps);
                        }
                        combined.addAll(profileManagedDeps);
                    }
                }
                rawManagedDeps = combined == null ? mainManagedDeps : combined;
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

    private static boolean isMaybeExtension(Artifact a) {
        var type = a.getExtension();
        var classifier = a.getClassifier();
        return (type == null
                || type.isEmpty()
                || type.equals(ArtifactCoords.TYPE_JAR))
                && (classifier == null
                        || classifier.isEmpty()
                        || !classifier.equals("sources")
                                && !classifier.equals("javadoc"));
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

    private record PlatformVersionConstraint(String version, ArtifactCoords platformBom) {
    }
}
