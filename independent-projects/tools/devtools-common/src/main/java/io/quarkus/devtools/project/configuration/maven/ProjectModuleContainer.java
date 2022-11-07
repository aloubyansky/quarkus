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
        addQuarkusMavenPluginConfiguration(app);
        return app;
    }

    private void addQuarkusMavenPluginConfiguration(ConfiguredApplication app) {
        var quarkusPlugin = getBuildQuarkusPluginOrNull();
        if (quarkusPlugin == null) {
            quarkusPlugin = getManagedQuarkusPluginOrNull();
        }
        if (quarkusPlugin == null) {
            throw new RuntimeException("Failed to locate quarkus-maven-plugin configuration in " + app.getId());
        }
        app.setQuarkusPlugin(quarkusPlugin);
    }

    private void collectTopExtensionDeps(ConfiguredApplication app) {
        var taskRunner = ModelResolutionTaskRunner.getNonBlockingTaskRunner();
        final Map<ArtifactKey, Artifact> collectedDirectDeps = new ConcurrentHashMap<>();
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
            var key = ArtifactKey.of(groupId.getValue(), artifactId.getValue(), classifier.getValue(), type.getValue());
            var extensionArtifact = extensions.remove(key);
            if (extensionArtifact != null) {
                ConfiguredArtifact configured;
                if (directDep.getVersion() == null) {
                    configured = getConfiguredDirectVersionedDependencyOrNull(key);
                    if (configured == null) {
                        var managed = getConfiguredManagedDependencyOrNull(key);
                        if (managed == null) {
                            throw new RuntimeException(
                                    "Failed to determine the version of " + key + " configured in " + getPomFile());
                        }
                        configured = ConfiguredArtifact.of(ConfiguredValue.of(directDep.getGroupId(), groupId),
                                ConfiguredValue.of(directDep.getArtifactId(), artifactId),
                                ConfiguredValue.of(directDep.getClassifier(), classifier),
                                ConfiguredValue.of(directDep.getType(), type),
                                ConfiguredValue.of(directDep.getVersion(), managed.getVersion().getResolvedValue()),
                                getPomFile());
                    }
                } else {
                    configured = ConfiguredArtifact.of(ConfiguredValue.of(directDep.getGroupId(), groupId),
                            ConfiguredValue.of(directDep.getArtifactId(), artifactId),
                            ConfiguredValue.of(directDep.getClassifier(), classifier),
                            ConfiguredValue.of(directDep.getType(), type),
                            ConfiguredValue.of(directDep.getVersion(), resolveValue(directDep.getVersion())),
                            getPomFile());
                }
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
            var configuredBom = locateBomImport(platformBom);
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

    private ConfiguredBom locateBomImport(ArtifactCoords platformBom) {
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
                    return ConfiguredBom.imported(bomArtifact);
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
                return ((ProjectModuleContainer) nextSource).locateBomImport(platformBom);
            }
            return ConfiguredBom.enforced(
                    ConfiguredValue.of(platformBom.getGroupId(), nextSource.resolveValue(platformBom.getGroupId())),
                    ConfiguredValue.of(platformBom.getArtifactId(), nextSource.resolveValue(platformBom.getArtifactId())),
                    ConfiguredValue.of(platformBom.getVersion(), nextSource.resolveValue(platformBom.getVersion())), null,
                    false);
        }
        throw new RuntimeException("Failed to locate the source of " + platformBom.toCompactCoords() + " import");
    }

    private ConfiguredArtifact getManagedQuarkusPluginOrNull() {
        var managedPlugins = getRawManagedPlugins();
        for (var plugin : managedPlugins) {
            if (plugin.getVersion() != null) {
                var artifactIdValue = resolveValue(plugin.getArtifactId());
                if ("quarkus-maven-plugin".equals(artifactIdValue.getValue())) {
                    return ConfiguredArtifact.jar(ConfiguredValue.of(plugin.getGroupId(), resolveValue(plugin.getGroupId())),
                            ConfiguredValue.of(plugin.getArtifactId(), artifactIdValue),
                            ConfiguredValue.of(plugin.getVersion(), resolveValue(plugin.getVersion())),
                            getPomFile());
                }
            }
        }
        var parentModule = getParentModuleContainer();
        if (parentModule == null) {
            return null;
        }
        if (parentModule.isProjectModule()) {
            return ((ProjectModuleContainer) parentModule).getManagedQuarkusPluginOrNull();
        }
        return getExternallyConfiguredQuarkusPluginOrNull(parentModule.getEffectiveModel());
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

    /**
     * Returns configured quarkus-maven-plugin or null. The method is looking for
     * a quarkus-maven-plugin configuration with version. If it can't find in the current module
     * it will look for it in the parent. It does not check pluginManagement, since this is how Maven
     * determines plugin versions: build plugin versions from parent modules are preferred over those
     * configured in the pluginManagement.
     *
     * @return configured quarkus-maven-plugin or null
     */
    private ConfiguredArtifact getBuildQuarkusPluginOrNull() {
        var buildPlugins = getRawBuildPlugins();
        for (var plugin : buildPlugins) {
            if (plugin.getVersion() != null) {
                var artifactIdValue = resolveValue(plugin.getArtifactId());
                if ("quarkus-maven-plugin".equals(artifactIdValue.getValue())) {
                    return ConfiguredArtifact.jar(ConfiguredValue.of(plugin.getGroupId(), resolveValue(plugin.getGroupId())),
                            ConfiguredValue.of(plugin.getArtifactId(), artifactIdValue),
                            ConfiguredValue.of(plugin.getVersion(), resolveValue(plugin.getVersion())),
                            getPomFile());
                }
            }
        }
        var parentModule = getParentModuleContainer();
        if (parentModule == null) {
            return null;
        }
        if (parentModule.isProjectModule()) {
            return ((ProjectModuleContainer) parentModule).getBuildQuarkusPluginOrNull();
        }
        return getExternallyConfiguredQuarkusPluginOrNull(parentModule.getEffectiveModel());
    }

    private List<Plugin> getRawBuildPlugins() {
        List<Plugin> buildPlugins = getRawBuildPlugins(module.getRawModel().getBuild());
        final List<Profile> profiles = getActivePomProfiles();
        if (profiles.isEmpty()) {
            return buildPlugins;
        }
        List<Plugin> combined = null;
        for (Profile p : profiles) {
            var profileBuildPlugins = getRawBuildPlugins(p.getBuild());
            if (!profileBuildPlugins.isEmpty()) {
                if (combined == null) {
                    combined = new ArrayList<>();
                    combined.addAll(buildPlugins);
                }
                combined.addAll(profileBuildPlugins);
            }
        }
        return combined == null ? buildPlugins : combined;
    }

    private static List<Plugin> getRawBuildPlugins(BuildBase build) {
        return build == null ? List.of() : build.getPlugins();
    }

    private ConfiguredArtifact getConfiguredDirectVersionedDependencyOrNull(ArtifactKey key) {
        var directDeps = getRawDirectDeps();
        for (var directDep : directDeps) {
            if (directDep.getVersion() != null) {
                var artifactIdValue = resolveValue(directDep.getArtifactId());
                if (key.getArtifactId().equals(artifactIdValue.getValue())) {
                    var groupIdValue = resolveValue(directDep.getGroupId());
                    if (key.getGroupId().equals(groupIdValue.getValue())) {
                        ResolvedValue typeValue = null;
                        if (key.getType().equals(ArtifactCoords.TYPE_JAR) &&
                                (directDep.getType() == null || directDep.getType().equals(ArtifactCoords.TYPE_JAR)) ||
                                key.getType().equals(directDep.getType())) {
                            // the types match
                        } else {
                            typeValue = resolveValue(directDep.getType());
                            if (!key.getType().equals(typeValue.getValue())) {
                                continue;
                            }
                        }

                        ResolvedValue classifierValue = null;
                        if (key.getClassifier().isEmpty() &&
                                (directDep.getClassifier() == null || directDep.getClassifier().isEmpty()) ||
                                key.getClassifier().equals(directDep.getClassifier())) {
                            // the classifiers match
                        } else {
                            classifierValue = resolveValue(directDep.getClassifier());
                            if (!key.getClassifier().equals(classifierValue.getValue())) {
                                continue;
                            }
                        }

                        return ConfiguredArtifact.of(
                                ConfiguredValue.of(directDep.getGroupId(), groupIdValue),
                                ConfiguredValue.of(directDep.getArtifactId(), artifactIdValue),
                                ConfiguredValue.of(directDep.getClassifier(),
                                        classifierValue == null ? resolveValue(directDep.getClassifier()) : classifierValue),
                                ConfiguredValue.of(directDep.getType(),
                                        typeValue == null ? resolveValue(directDep.getClassifier()) : classifierValue),
                                ConfiguredValue.of(directDep.getVersion(), resolveValue(directDep.getVersion())),
                                getPomFile());
                    }
                }
            }
        }
        var parentModule = getParentModuleContainer();
        if (parentModule == null) {
            return null;
        }
        if (parentModule.isProjectModule()) {
            return ((ProjectModuleContainer) parentModule).getConfiguredDirectVersionedDependencyOrNull(key);
        }
        return null;
    }

    private ConfiguredArtifact getConfiguredManagedDependencyOrNull(ArtifactKey key) {
        var managedDeps = getRawManagedDeps();
        List<ConfiguredArtifact> importedBoms = null;
        for (var managedDep : managedDeps) {
            if (managedDep.getVersion() != null) {
                var artifactIdValue = resolveValue(managedDep.getArtifactId());
                if (key.getArtifactId().equals(artifactIdValue.getValue())) {
                    var groupIdValue = resolveValue(managedDep.getGroupId());
                    if (key.getGroupId().equals(groupIdValue.getValue())) {
                        ResolvedValue typeValue = null;
                        if (key.getType().equals(ArtifactCoords.TYPE_JAR) &&
                                (managedDep.getType() == null || managedDep.getType().equals(ArtifactCoords.TYPE_JAR)) ||
                                key.getType().equals(managedDep.getType())) {
                            // the types match
                        } else {
                            typeValue = resolveValue(managedDep.getType());
                            if (!key.getType().equals(typeValue.getValue())) {
                                continue;
                            }
                        }

                        ResolvedValue classifierValue = null;
                        if (key.getClassifier().isEmpty() &&
                                (managedDep.getClassifier() == null || managedDep.getClassifier().isEmpty()) ||
                                key.getClassifier().equals(managedDep.getClassifier())) {
                            // the classifiers match
                        } else {
                            classifierValue = resolveValue(managedDep.getClassifier());
                            if (!key.getClassifier().equals(classifierValue.getValue())) {
                                continue;
                            }
                        }

                        return ConfiguredArtifact.of(
                                ConfiguredValue.of(managedDep.getGroupId(), groupIdValue),
                                ConfiguredValue.of(managedDep.getArtifactId(), artifactIdValue),
                                ConfiguredValue.of(managedDep.getClassifier(),
                                        classifierValue == null ? resolveValue(managedDep.getClassifier()) : classifierValue),
                                ConfiguredValue.of(managedDep.getType(),
                                        typeValue == null ? resolveValue(managedDep.getClassifier()) : classifierValue),
                                ConfiguredValue.of(managedDep.getVersion(), resolveValue(managedDep.getVersion())),
                                getPomFile());
                    }
                }
                if ("import".equals(managedDep.getScope()) && ArtifactCoords.TYPE_POM.equals(managedDep.getType())) {
                    if (importedBoms == null) {
                        importedBoms = new ArrayList<>();
                    }
                    importedBoms.add(ConfiguredArtifact.pom(
                            ConfiguredValue.of(managedDep.getGroupId(), resolveValue(managedDep.getGroupId())),
                            ConfiguredValue.of(managedDep.getArtifactId(), artifactIdValue),
                            ConfiguredValue.of(managedDep.getVersion(), resolveValue(managedDep.getVersion())),
                            getPomFile()));
                }
            }
        }

        if (importedBoms != null) {
            for (ConfiguredArtifact importedBom : importedBoms) {
                var bomModule = appConfigResolver
                        .resolveModuleContainer(WorkspaceModuleId.of(importedBom.getGroupId().getEffectiveValue(),
                                importedBom.getArtifactId().getEffectiveValue(),
                                importedBom.getVersion().getEffectiveValue()));
                var constraint = bomModule.getEnforcedVersionConstraintOrNull(key);
                if (constraint != null) {
                    if (bomModule.isProjectModule()) {
                        return ((ProjectModuleContainer) bomModule).getConfiguredManagedDependencyOrNull(key);
                    }
                    ConfiguredValue versionValue;
                    Path configurationFile = null;
                    if (constraint.getGroupId().equals(bomModule.getId().getGroupId())
                            && constraint.getVersion().equals(bomModule.getId().getVersion())) {
                        // an optimistic guess and a hack to get updates bump the version of the BOM managing the dependency
                        versionValue = ConfiguredValue.of(null, importedBom.getVersion().getResolvedValue());
                        configurationFile = getPomFile();
                    } else {
                        versionValue = ConfiguredValue.of(constraint.getVersion(),
                                bomModule.resolveValue(constraint.getVersion()));
                    }
                    return ConfiguredArtifact.of(
                            ConfiguredValue.of(constraint.getGroupId(), bomModule.resolveValue(constraint.getGroupId())),
                            ConfiguredValue.of(constraint.getArtifactId(), bomModule.resolveValue(constraint.getArtifactId())),
                            ConfiguredValue.of(constraint.getClassifier(), bomModule.resolveValue(constraint.getClassifier())),
                            ConfiguredValue.of(constraint.getType(), bomModule.resolveValue(constraint.getType())),
                            versionValue, configurationFile);
                }
            }
        }

        var parentModule = getParentModuleContainer();
        if (parentModule == null) {
            return null;
        }
        if (parentModule.isProjectModule()) {
            return ((ProjectModuleContainer) parentModule).getConfiguredManagedDependencyOrNull(key);
        }
        return null;
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

    private static ConfiguredArtifact getExternallyConfiguredQuarkusPluginOrNull(Model effectiveModel) {
        var build = effectiveModel.getBuild();
        if (build != null) {
            for (var plugin : build.getPlugins()) {
                if ("quarkus-maven-plugin".equals(plugin.getArtifactId())) {
                    return ConfiguredArtifact.jar(
                            ConfiguredValue.of(plugin.getGroupId()),
                            ConfiguredValue.of(plugin.getArtifactId()),
                            ConfiguredValue.of(plugin.getVersion()),
                            null);
                }
            }
        }
        return null;
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
