package io.quarkus.devtools.project.configuration.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.ModelResolutionTaskRunner;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredArtifact;
import io.quarkus.devtools.project.configuration.ConfiguredBom;
import io.quarkus.devtools.project.configuration.ConfiguredModule;
import io.quarkus.devtools.project.configuration.ConfiguredProject;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.devtools.project.configuration.ResolvedValue;
import io.quarkus.devtools.project.configuration.ValueSource;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.*;
import io.quarkus.registry.util.PlatformArtifacts;

public class MavenProjectConfigurationLoader {

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
        return new MavenProjectConfigurationLoader(mavenCtx, log).loadInternal(projectDir);
    }

    private final Map<ArtifactKey, ModuleWrapper> modules = new ConcurrentHashMap<>();
    private final Map<ArtifactCoords, Map<ArtifactKey, String>> externalBoms = new ConcurrentHashMap<>();
    private final BootstrapMavenContext mavenContext;
    private final MessageWriter log;

    private MavenProjectConfigurationLoader(BootstrapMavenContext mavenContext, MessageWriter log) {
        this.mavenContext = mavenContext;
        this.log = log;
    }

    private ConfiguredProject loadInternal(Path projectDir) {

        log.debug("Loading project configuration for %s", projectDir);
        final long start = System.currentTimeMillis();

        final LocalWorkspace ws = mavenContext.getWorkspace();
        final Collection<LocalProject> projects = ws.getProjects().values();
        final List<Path> createdDirs = ensureResolvable(projects);
        try {
            if (projects.size() > 1) {
                loadModulesInParallel(projects);
            } else {
                loadModulesInSequence(projects);
            }
        } finally {
            for (Path p : createdDirs) {
                IoUtils.recursiveDelete(p);
            }
        }
        final ConfiguredProject project = new ConfiguredProject(projectDir);
        for (ModuleWrapper md : modules.values()) {
            if (md.getPomFile().startsWith(projectDir)) {
                project.addModule(md.getConfiguredModule());
            }
        }
        log.info("Read project in " + (System.currentTimeMillis() - start));
        return project;
    }

    private void loadModulesInSequence(Collection<LocalProject> projects) {
        for (LocalProject project : projects) {
            loadModule(project);
        }
    }

    private void loadModulesInParallel(Collection<LocalProject> projects) {
        var taskRunner = ModelResolutionTaskRunner.getNonBlockingTaskRunner();
        for (LocalProject project : projects) {
            taskRunner.run(() -> loadModule(project));
        }
        taskRunner.waitForCompletion();
    }

    private void loadModule(LocalProject project) {
        final ModuleWrapper moduleWrapper = getModuleWrapper(project);
        if (!moduleWrapper.effectiveDirectDeps.isEmpty()) {
            processDirectDeps(moduleWrapper);
        }

        if (moduleWrapper.quarkusPlugin != null) {
            var plugin = moduleWrapper.quarkusPlugin;
            final Plugin effectivePlugin = moduleWrapper.getEffectivePluginConfig(
                    plugin.getGroupId().getEffectiveValue(), plugin.getArtifactId().getEffectiveValue());
            if (effectivePlugin != null) {
                if (!ArtifactCoords.TYPE_POM.equals(moduleWrapper.getRawModel().getPackaging())) {
                    for (PluginExecution e : effectivePlugin.getExecutions()) {
                        if (e.getGoals().contains("build")) {
                            moduleWrapper.getConfiguredModule().setQuarkusApplication(true);
                            break;
                        }
                    }
                }
            }
            if (plugin.getVersion().isEffectivelyNull()) {
                ResolvedValue managedVersion = getManagedPluginVersion(moduleWrapper, ArtifactKey.ga(
                        plugin.getGroupId().getEffectiveValue(), plugin.getArtifactId().getEffectiveValue()));
                if (managedVersion == null && effectivePlugin != null) {
                    managedVersion = ResolvedValue.of(effectivePlugin.getVersion());
                }
                if (managedVersion != null) {
                    plugin = ConfiguredArtifact.of(plugin.getGroupId(), plugin.getArtifactId(),
                            plugin.getClassifier(), plugin.getType(),
                            ConfiguredValue.of(plugin.getVersion().getRawValue(), managedVersion),
                            plugin.getConfigurationFile());
                    moduleWrapper.setQuarkusPlugin(plugin);
                }
            }
        }
    }

    private void processDirectDeps(ModuleWrapper moduleWrapper) {
        final Map<ArtifactKey, ConfiguredArtifact> rawDeps = new HashMap<>(moduleWrapper.getRawDirectDeps().size());
        for (org.apache.maven.model.Dependency d : moduleWrapper.getRawDirectDeps()) {
            final String type = d.getType().isEmpty() ? ArtifactCoords.TYPE_JAR : d.getType();
            if (isPotentialExtension(type, d.getClassifier())) {
                var groupId = ConfiguredValue.of(d.getGroupId(), resolveValue(d.getGroupId(), moduleWrapper));
                var artifactId = ConfiguredValue.of(d.getArtifactId(),
                        resolveValue(d.getArtifactId(), moduleWrapper));
                var version = ConfiguredValue.of(d.getVersion(), resolveValue(d.getVersion(), moduleWrapper));
                final ConfiguredArtifact c = ConfiguredArtifact.of(groupId, artifactId,
                        ConfiguredValue.of(d.getClassifier(),
                                resolveValue(d.getClassifier(), moduleWrapper)),
                        ConfiguredValue.of(d.getType(), resolveValue(type, moduleWrapper)),
                        version,
                        moduleWrapper.getPomFile(),
                        !version.isEffectivelyNull()
                                && isLocal(groupId.getEffectiveValue(), artifactId.getEffectiveValue(),
                                        version.getEffectiveValue()));
                rawDeps.put(c.getKey(), c);
            }
        }

        // parallelizing this had a negative effect on the processing time
        for (Dependency d : moduleWrapper.effectiveDirectDeps) {
            final Artifact artifact = d.getArtifact();
            if (isPotentialExtension(artifact.getExtension(), artifact.getClassifier())) {
                processDirectDep(moduleWrapper, artifact, rawDeps);
            }
        }
    }

    private void processDirectDep(ModuleWrapper moduleWrapper, Artifact artifact,
            Map<ArtifactKey, ConfiguredArtifact> rawDeps) {
        var a = resolveModuleArtifact(moduleWrapper, artifact).getArtifact();
        PathTree.ofDirectoryOrArchive(a.getFile().toPath())
                .accept(BootstrapConstants.DESCRIPTOR_PATH, visit -> {
                    if (visit == null) {
                        return;
                    }
                    final ArtifactKey key = ArtifactKey.of(a.getGroupId(), a.getArtifactId(), a.getClassifier(),
                            a.getExtension());
                    ConfiguredArtifact c = rawDeps.get(key);
                    if (c != null) {
                        if (c.getVersion().isEffectivelyNull()) {
                            ResolvedValue managedVersion = getManagedDependencyVersion(moduleWrapper, key);
                            if (managedVersion == null) {
                                managedVersion = ResolvedValue.of(a.getVersion());
                            }
                            c = ConfiguredArtifact.of(c.getGroupId(), c.getArtifactId(), c.getClassifier(),
                                    c.getType(),
                                    ConfiguredValue.of(c.getVersion().getRawValue(), managedVersion),
                                    c.getConfigurationFile(),
                                    isLocal(a.getGroupId(), a.getArtifactId(), a.getVersion()));
                        }
                        moduleWrapper.getConfiguredModule().addDirectExtensionDep(c);
                    } else {
                        final ResolvedValue resolvedVersion = getInheritedDependencyVersion(moduleWrapper, a);
                        c = ConfiguredArtifact.of(ConfiguredValue.of(a.getGroupId()),
                                ConfiguredValue.of(a.getArtifactId()),
                                ConfiguredValue.of(a.getClassifier()),
                                ConfiguredValue.of(a.getExtension()),
                                ConfiguredValue.of(null, resolvedVersion),
                                resolvedVersion.getSource().getPath(),
                                isLocal(a.getGroupId(), a.getArtifactId(), a.getVersion()));
                        moduleWrapper.getConfiguredModule().addDirectExtensionDep(c);
                    }
                });
    }

    private ArtifactResult resolveModuleArtifact(ModuleWrapper moduleWrapper, Artifact artifact) {
        try {
            final RepositorySystem system = mavenContext.getRepositorySystem();
            final RepositorySystemSession session = mavenContext.getRepositorySystemSession();
            return system.resolveArtifact(session,
                    new ArtifactRequest()
                            .setArtifact(artifact)
                            .setRepositories(system.newResolutionRepositories(
                                    session, moduleWrapper.effectiveRepos)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve " + artifact, e);
        }
    }

    private static List<Path> ensureResolvable(Collection<LocalProject> modules) {
        final List<Path> createdDirs = new ArrayList<>();
        for (var project : modules) {
            ensureResolvable(project, createdDirs);
        }
        return createdDirs;
    }

    private static boolean isPotentialExtension(String type, String classifier) {
        return (type == null
                || type.isEmpty()
                || type.equals(ArtifactCoords.TYPE_JAR))
                && (classifier == null
                        || classifier.isEmpty()
                        || !classifier.equals("sources")
                                && !classifier.equals("javadoc"));
    }

    private boolean isLocal(String groupId, String artifactId, String version) {
        final LocalProject project = mavenContext.getWorkspace().getProject(groupId, artifactId);
        return project != null && project.getVersion().equals(version);
    }

    private ModuleWrapper getModuleWrapper(LocalProject project) {
        ModuleWrapper module = this.modules.get(project.getKey());
        if (module == null) {
            List<Dependency> effectiveManagedDeps = List.of();
            final List<Dependency> effectiveDirectDeps;
            final List<RemoteRepository> effectiveRepos;
            if (project.getModelBuildingResult() == null) {
                ArtifactDescriptorResult descriptor;
                try {
                    descriptor = mavenContext.getRepositorySystem().readArtifactDescriptor(
                            mavenContext.getRepositorySystemSession(),
                            new ArtifactDescriptorRequest()
                                    .setArtifact(new DefaultArtifact(project.getGroupId(), project.getArtifactId(),
                                            ArtifactCoords.TYPE_POM, project.getVersion()))
                                    .setRepositories(mavenContext.getRemoteRepositories()));
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed to resolve artifact descriptor for "
                                    + ArtifactCoords.pom(project.getGroupId(), project.getArtifactId(), project.getVersion()),
                            e);
                }
                effectiveManagedDeps = descriptor.getManagedDependencies();
                effectiveDirectDeps = descriptor.getDependencies();
                effectiveRepos = descriptor.getRepositories();
            } else {
                final Model em = project.getModelBuildingResult().getEffectiveModel();
                final DependencyManagement dm = em.getDependencyManagement();
                if (dm != null) {
                    effectiveManagedDeps = toAetherDeps(dm.getDependencies());
                }
                effectiveDirectDeps = toAetherDeps(em.getDependencies());
                effectiveRepos = new ArrayList<>(em.getRepositories().size());
                for (org.apache.maven.model.Repository r : em.getRepositories()) {
                    final RemoteRepository.Builder rb = new RemoteRepository.Builder(r.getId(), r.getLayout(), r.getUrl())
                            .setContentType(r.getLayout());
                    var rp = r.getReleases();
                    if (rp != null) {
                        rb.setReleasePolicy(new RepositoryPolicy(Boolean.parseBoolean(rp.getEnabled()),
                                rp.getUpdatePolicy() == null ? RepositoryPolicy.UPDATE_POLICY_NEVER : rp.getUpdatePolicy(),
                                rp.getChecksumPolicy() == null ? RepositoryPolicy.CHECKSUM_POLICY_WARN
                                        : rp.getChecksumPolicy()));
                    }
                    rp = r.getSnapshots();
                    if (rp != null) {
                        rb.setSnapshotPolicy(new RepositoryPolicy(Boolean.parseBoolean(rp.getEnabled()),
                                rp.getUpdatePolicy() == null ? RepositoryPolicy.UPDATE_POLICY_DAILY : rp.getUpdatePolicy(),
                                rp.getChecksumPolicy() == null ? RepositoryPolicy.CHECKSUM_POLICY_WARN
                                        : rp.getChecksumPolicy()));
                    }
                    effectiveRepos.add(rb.build());
                }
            }
            module = new ModuleWrapper(project, effectiveManagedDeps, effectiveDirectDeps, effectiveRepos);
            collectManagedDeps(module);
            modules.put(project.getKey(), module);
        }
        return module;
    }

    private List<Dependency> toAetherDeps(List<org.apache.maven.model.Dependency> deps) {
        final List<Dependency> result = new ArrayList<>(deps.size());
        for (org.apache.maven.model.Dependency d : deps) {
            result.add(new Dependency(
                    new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType(), d.getVersion()),
                    d.getScope()));
        }
        return result;
    }

    private ResolvedValue getInheritedDependencyVersion(ModuleWrapper module, Artifact a) {
        for (org.apache.maven.model.Dependency d : module.getRawDirectDeps()) {
            if (a.getArtifactId().equals(d.getArtifactId()) && a.getGroupId()
                    .equals(d.getGroupId().equals("${project.groupId}") ? module.getGroupId() : d.getGroupId())
                    && a.getClassifier()
                            .equals(d.getClassifier() == null ? ArtifactCoords.DEFAULT_CLASSIFIER : d.getClassifier())
                    && a.getExtension().equals(d.getType() == null ? ArtifactCoords.TYPE_JAR : d.getType())) {
                return ResolvedValue.of(a.getVersion(), ValueSource.local(module.getId(), module.getPomFile()));
            }
        }
        final LocalProject parent = module.project.getLocalParent();
        if (parent != null) {
            return getInheritedDependencyVersion(getModuleWrapper(parent), a);
        }
        final Parent parentModel = module.project.getRawModel().getParent();
        if (parentModel != null) {
            return ResolvedValue.of(a.getVersion(), ValueSource.external(
                    WorkspaceModuleId.of(parentModel.getGroupId(), parentModel.getArtifactId(), parentModel.getVersion()),
                    null));
        }
        return ResolvedValue.of(a.getVersion());
    }

    private ResolvedValue getManagedDependencyVersion(ModuleWrapper moduleWrapper, ArtifactKey key) {
        final ConfiguredArtifact configured = moduleWrapper.getExtensionVersionConstraint(key);
        if (configured != null) {
            return configured.getVersion().getResolvedValue();
        }
        final LocalProject parent = moduleWrapper.project.getLocalParent();
        if (parent != null) {
            final ResolvedValue v = getManagedDependencyVersion(getModuleWrapper(parent), key);
            if (v != null) {
                return v;
            }
        }
        for (ArtifactCoords bom : moduleWrapper.bomImports.keySet()) {
            final LocalProject bomProject = mavenContext.getWorkspace().getProject(bom.getGroupId(), bom.getArtifactId());
            if (bomProject != null) {
                final ResolvedValue version = getManagedDependencyVersion(getModuleWrapper(bomProject), key);
                if (version != null) {
                    return version;
                }
            } else {
                final String version = getExternalBom(bom).get(key);
                if (version != null) {
                    return ResolvedValue.of(version,
                            ValueSource.external(WorkspaceModuleId.of(bom.getGroupId(), bom.getArtifactId(), bom.getVersion()),
                                    null)); // TODO
                }
            }
        }
        return null;
    }

    private ConfiguredBom locateBomImport(ModuleWrapper moduleWrapper, ArtifactCoords bom) {
        ConfiguredBom configured = moduleWrapper.bomImports.get(bom);
        if (configured != null) {
            return configured;
        }
        final LocalProject parent = moduleWrapper.project.getLocalParent();
        if (parent != null) {
            configured = locateBomImport(getModuleWrapper(parent), bom);
            if (configured != null) {
                return configured;
            }
        } else if (moduleWrapper.project.getRawModel().getParent() != null) {
            final Parent parentPom = moduleWrapper.project.getRawModel().getParent();
            final ArtifactCoords pom = ArtifactCoords.pom(parentPom.getGroupId(), parentPom.getArtifactId(),
                    parentPom.getVersion());
            if (importsPlatformBom(pom, bom)) {
                final WorkspaceModuleId externalId = WorkspaceModuleId.of(pom.getGroupId(), pom.getArtifactId(),
                        pom.getVersion());
                return ConfiguredBom.enforced(
                        ConfiguredValue.of(bom.getGroupId(),
                                ResolvedValue.of(bom.getGroupId(), ValueSource.external(externalId, null))),
                        ConfiguredValue.of(bom.getArtifactId(),
                                ResolvedValue.of(bom.getArtifactId(), ValueSource.external(externalId, null))),
                        ConfiguredValue.of(bom.getVersion(),
                                ResolvedValue.of(bom.getVersion(), ValueSource.external(externalId, null))),
                        null,
                        false);
            }
        }
        for (Map.Entry<ArtifactCoords, ConfiguredBom> imported : moduleWrapper.bomImports.entrySet()) {
            if (imported.getKey().equals(bom)) {
                return imported.getValue();
            }
            if (imported.getValue().getArtifact().isLocal()) {
                final LocalProject bomProject = mavenContext.getWorkspace().getProject(imported.getKey().getGroupId(),
                        imported.getKey().getArtifactId());
                if (bomProject != null) {
                    var located = locateBomImport(getModuleWrapper(bomProject), bom);
                    if (located != null) {
                        return located;
                    }
                }
            } else if (importsPlatformBom(imported.getKey(), bom)) {
                final WorkspaceModuleId externalId = WorkspaceModuleId.of(imported.getKey().getGroupId(),
                        imported.getKey().getArtifactId(), imported.getKey().getVersion());
                return ConfiguredBom.enforced(
                        ConfiguredValue.of(bom.getGroupId(),
                                ResolvedValue.of(bom.getGroupId(), ValueSource.external(externalId, null))),
                        ConfiguredValue.of(bom.getArtifactId(),
                                ResolvedValue.of(bom.getArtifactId(), ValueSource.external(externalId, null))),
                        ConfiguredValue.of(bom.getVersion(),
                                ResolvedValue.of(bom.getVersion(), ValueSource.external(externalId, null))),
                        null,
                        false);
            }
        }
        return null;
    }

    private boolean importsPlatformBom(ArtifactCoords pom, ArtifactCoords platformBom) {
        //log("importsPlatformBom " + pom.toCompactCoords() + " " + platformBom.toCompactCoords());
        return getExternalBom(pom).containsKey(PlatformArtifacts.ensureCatalogArtifact(platformBom).getKey());
    }

    private Map<ArtifactKey, String> getExternalBom(ArtifactCoords coords) {
        Map<ArtifactKey, String> managed = externalBoms.get(coords);
        if (managed == null) {
            final List<Dependency> deps;
            try {
                deps = mavenContext.getRepositorySystem().readArtifactDescriptor(mavenContext.getRepositorySystemSession(),
                        new ArtifactDescriptorRequest().setArtifact(
                                new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), ArtifactCoords.TYPE_POM,
                                        coords.getVersion()))
                                .setRepositories(mavenContext.getRemoteRepositories()))
                        .getManagedDependencies();
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve descriptor for " + coords.toCompactCoords(), e);
            }
            managed = new HashMap<>(deps.size());
            for (Dependency d : deps) {
                final Artifact a = d.getArtifact();
                managed.put(ArtifactKey.of(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension()),
                        a.getVersion());
            }
            externalBoms.put(coords, managed);
        }
        return managed;
    }

    private ResolvedValue getManagedPluginVersion(ModuleWrapper moduleWrapper, ArtifactKey pluginKey) {
        final ConfiguredArtifact configured = moduleWrapper.getPluginVersionConstraint(pluginKey);
        if (configured != null) {
            return configured.getVersion().getResolvedValue();
        }
        final LocalProject parent = moduleWrapper.project.getLocalParent();
        if (parent != null) {
            return getManagedPluginVersion(getModuleWrapper(parent), pluginKey);
        }
        return null;
    }

    private void collectManagedDeps(ModuleWrapper moduleWrapper) {

        if (!moduleWrapper.getRawManagedDeps().isEmpty()) {
            for (org.apache.maven.model.Dependency d : moduleWrapper.getRawManagedDeps()) {
                final ConfiguredValue groupId = ConfiguredValue.of(d.getGroupId(), resolveValue(d.getGroupId(), moduleWrapper));
                final ConfiguredValue artifactId = ConfiguredValue.of(d.getArtifactId(),
                        resolveValue(d.getArtifactId(), moduleWrapper));
                final ConfiguredValue version = ConfiguredValue.of(d.getVersion(), resolveValue(d.getVersion(), moduleWrapper));
                if ("import".equals(d.getScope())) {
                    final ArtifactCoords effectiveCoords = ArtifactCoords.pom(groupId.getEffectiveValue(),
                            artifactId.getEffectiveValue(), version.getEffectiveValue());
                    moduleWrapper.bomImports.put(effectiveCoords,
                            ConfiguredBom.imported(groupId, artifactId, version,
                                    moduleWrapper.getPomFile(),
                                    isLocal(groupId.getEffectiveValue(), artifactId.getEffectiveValue(),
                                            version.getEffectiveValue())));
                } else {
                    final String classifier = d.getClassifier() == null ? ArtifactCoords.DEFAULT_CLASSIFIER : d.getClassifier();
                    final ConfiguredArtifact artifact = ConfiguredArtifact.of(
                            groupId, artifactId,
                            ConfiguredValue.of(classifier, resolveValue(classifier, moduleWrapper)),
                            ConfiguredValue.of(d.getType(), resolveValue(d.getType(), moduleWrapper)),
                            version,
                            moduleWrapper.getPomFile(),
                            isLocal(groupId.getEffectiveValue(), artifactId.getEffectiveValue(), version.getEffectiveValue()));
                    moduleWrapper.managedDeps.put(artifact.getKey(), artifact);
                }
            }
        }

        final Collection<ArtifactCoords> enforcedPlatformBoms = getEnforcedPlatformBoms(moduleWrapper.effectiveManagedDeps);
        for (ArtifactCoords enforced : enforcedPlatformBoms) {
            ConfiguredBom platformBom = moduleWrapper.bomImports.get(enforced);
            if (platformBom != null) {
                moduleWrapper.getConfiguredModule().addPlatformBom(platformBom);
            } else {
                platformBom = locateBomImport(moduleWrapper, enforced);
                if (platformBom == null) {
                    moduleWrapper.getConfiguredModule().addPlatformBom(
                            ConfiguredBom.enforced(
                                    ConfiguredValue.of(enforced.getGroupId()),
                                    ConfiguredValue.of(enforced.getArtifactId()),
                                    ConfiguredValue.of(enforced.getVersion()),
                                    null,
                                    isLocal(enforced.getGroupId(), enforced.getArtifactId(), enforced.getVersion())));
                } else {
                    moduleWrapper.getConfiguredModule().addPlatformBom(ConfiguredBom.enforced(platformBom.getArtifact()));
                }
            }
        }

        for (Plugin rawPlugin : moduleWrapper.getRawPlugins()) {
            final ResolvedValue artifactId = resolveValue(rawPlugin.getArtifactId(), moduleWrapper);
            if ("quarkus-maven-plugin".equals(artifactId.getValue())) {
                moduleWrapper.setQuarkusPlugin(ConfiguredArtifact.jar(
                        ConfiguredValue.of(rawPlugin.getGroupId(), resolveValue(rawPlugin.getGroupId(), moduleWrapper)),
                        ConfiguredValue.of(rawPlugin.getArtifactId(), artifactId),
                        ConfiguredValue.of(rawPlugin.getVersion(), resolveValue(rawPlugin.getVersion(), moduleWrapper)),
                        moduleWrapper.getPomFile()));
                break;
            }
        }
        for (Plugin plugin : moduleWrapper.getRawManagedPlugins()) {
            if (plugin.getVersion() != null) {
                moduleWrapper.managedPlugins.put(
                        ArtifactKey.ga(resolveValue(plugin.getGroupId(), moduleWrapper).getValue(),
                                resolveValue(plugin.getArtifactId(), moduleWrapper).getValue()),
                        plugin);
            }
        }
    }

    private ResolvedValue resolveValue(String expr, PomWrapper rawModel) {
        return resolveValue(expr, null, rawModel);
    }

    private ResolvedValue resolveValue(String expr, String wrappingExpr, PomWrapper pomWrapper) {

        if (!ConfiguredValue.isPropertyExpression(expr)) {
            final LocalProject project = mavenContext.getWorkspace().getProject(pomWrapper.getGroupId(),
                    pomWrapper.getArtifactId());
            final ValueSource source = project == null
                    ? ValueSource.external(pomWrapper.getId(), wrappingExpr, pomWrapper.getPomFile())
                    : ValueSource.local(pomWrapper.getId(), wrappingExpr, pomWrapper.getPomFile());
            return ResolvedValue.of(expr, source);
        }
        final String name = ConfiguredValue.getPropertyName(expr);
        if (name.startsWith("project.")) {
            final String projectProp = name.substring("project.".length());
            switch (projectProp) {
                case "version":
                    return ResolvedValue.of(pomWrapper.getVersion(),
                            ValueSource.local(pomWrapper.getId(), name, pomWrapper.getPomFile()));
                case "groupId":
                    return ResolvedValue.of(pomWrapper.getGroupId(),
                            ValueSource.local(pomWrapper.getId(), name, pomWrapper.getPomFile()));
                case "artifactId":
                    return ResolvedValue.of(pomWrapper.getArtifactId(),
                            ValueSource.local(pomWrapper.getId(), name, pomWrapper.getPomFile()));
            }
        }
        final String value = pomWrapper.getRawProperties().getProperty(name);
        if (value != null) {
            return resolveValue(value, name, pomWrapper);
        }
        final PomWrapper parent = pomWrapper.getParentWrapper();
        if (parent == null) {
            return ResolvedValue.of(expr);
        }
        return resolveValue(expr, wrappingExpr, parent);
    }

    private PomWrapper getPomWrapper(Parent parent) {
        if (parent == null) {
            return null;
        }
        final LocalProject parentProject = mavenContext.getWorkspace().getProject(parent.getGroupId(), parent.getArtifactId());
        if (parentProject != null) {
            return getModuleWrapper(parentProject);
        }
        final Artifact pomArtifact = new DefaultArtifact(parent.getGroupId(),
                parent.getArtifactId(), ArtifactCoords.TYPE_POM, parent.getVersion());
        final File pomXml;
        try {
            pomXml = mavenContext.getRepositorySystem()
                    .resolveArtifact(mavenContext.getRepositorySystemSession(),
                            new ArtifactRequest().setArtifact(
                                    pomArtifact)
                                    .setRepositories(mavenContext.getRemoteRepositories()))
                    .getArtifact().getFile();
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve " + pomArtifact, e);
        }
        return new ModelWrapper(readModel(pomXml));
    }

    private static Model readModel(File pomXml) {
        final Model parentModel;
        try {
            parentModel = ModelUtils.readModel(pomXml.toPath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + pomXml, e);
        }
        parentModel.setPomFile(pomXml);
        return parentModel;
    }

    private static Collection<ArtifactCoords> getEnforcedPlatformBoms(List<Dependency> managedDeps) {
        final List<ArtifactCoords> enforcedPlatformBoms = new ArrayList<>(4);
        for (Dependency d : managedDeps) {
            final Artifact a = d.getArtifact();
            if (a.getExtension().equals("json")
                    && a.getArtifactId().endsWith(BootstrapConstants.PLATFORM_DESCRIPTOR_ARTIFACT_ID_SUFFIX)
                    && a.getClassifier().equals(a.getVersion())) {
                enforcedPlatformBoms.add(ArtifactCoords.pom(a.getGroupId(),
                        PlatformArtifacts.ensureBomArtifactId(a.getArtifactId()), a.getVersion()));
            }
        }
        return enforcedPlatformBoms;
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

    private interface PomWrapper {

        String getGroupId();

        String getArtifactId();

        String getVersion();

        Properties getRawProperties();

        PomWrapper getParentWrapper();

        default WorkspaceModuleId getId() {
            return WorkspaceModuleId.of(getGroupId(), getArtifactId(), getVersion());
        }

        Path getPomFile();
    }

    private class ModuleWrapper implements PomWrapper {
        final LocalProject project;
        final List<Dependency> effectiveManagedDeps;
        final List<Dependency> effectiveDirectDeps;
        final List<RemoteRepository> effectiveRepos;

        private List<org.apache.maven.model.Dependency> rawDirectDeps;
        private List<org.apache.maven.model.Dependency> rawManagedDeps;
        private List<Plugin> rawPlugins;
        private List<Plugin> rawManagedPlugins;
        private Properties rawProps;
        private List<Profile> activePomProfiles;

        final Map<ArtifactKey, ConfiguredArtifact> managedDeps = new HashMap<>();
        final Map<ArtifactCoords, ConfiguredBom> bomImports = new LinkedHashMap<>();
        final Map<ArtifactKey, Plugin> managedPlugins = new HashMap<>();
        ConfiguredArtifact managedQuarkusPlugin;
        ConfiguredArtifact quarkusPlugin;

        ConfiguredModule module;

        public ModuleWrapper(LocalProject project, List<Dependency> effectiveManagedDeps,
                List<Dependency> effectiveDirectDeps,
                List<RemoteRepository> effectiveRepos) {
            super();
            this.project = project;
            this.effectiveManagedDeps = effectiveManagedDeps;
            this.effectiveDirectDeps = effectiveDirectDeps;
            this.effectiveRepos = effectiveRepos;
        }

        public void setQuarkusPlugin(ConfiguredArtifact quarkusPlugin) {
            this.quarkusPlugin = quarkusPlugin;
            getConfiguredModule().setQuarkusPlugin(quarkusPlugin);
        }

        private Plugin getEffectivePluginConfig(String groupId, String artifactId) {
            if (project.getModelBuildingResult() != null
                    && project.getModelBuildingResult().getEffectiveModel().getBuild() != null) {
                for (Plugin p : project.getModelBuildingResult().getEffectiveModel().getBuild().getPlugins()) {
                    if (p.getArtifactId().equals(artifactId) && p.getGroupId().equals(groupId)) {
                        return p;
                    }
                }
            }
            return null;
        }

        Model getRawModel() {
            return project.getRawModel();
        }

        List<org.apache.maven.model.Dependency> getRawDirectDeps() {
            if (rawDirectDeps == null) {
                final List<Profile> profiles = getActivePomProfiles();
                if (profiles.isEmpty()) {
                    rawDirectDeps = project.getRawModel().getDependencies();
                } else {
                    rawDirectDeps = new ArrayList<>();
                    rawDirectDeps.addAll(project.getRawModel().getDependencies());
                    for (Profile p : profiles) {
                        rawDirectDeps.addAll(p.getDependencies());
                    }
                }
            }
            return rawDirectDeps;
        }

        List<org.apache.maven.model.Dependency> getRawManagedDeps() {
            if (rawManagedDeps == null) {
                final List<Profile> profiles = getActivePomProfiles();
                if (profiles.isEmpty()) {
                    rawManagedDeps = project.getRawModel().getDependencyManagement() == null
                            ? List.of()
                            : project.getRawModel().getDependencyManagement().getDependencies();
                } else {
                    rawManagedDeps = new ArrayList<>();
                    if (project.getRawModel().getDependencyManagement() != null) {
                        rawManagedDeps.addAll(project.getRawModel().getDependencyManagement().getDependencies());
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

        List<Plugin> getRawPlugins() {
            if (rawPlugins == null) {
                final List<Profile> profiles = getActivePomProfiles();
                if (profiles.isEmpty()) {
                    rawPlugins = project.getRawModel().getBuild() == null ? List.of()
                            : project.getRawModel().getBuild().getPlugins();
                } else {
                    rawPlugins = new ArrayList<>();
                    if (project.getRawModel().getBuild() != null) {
                        rawPlugins.addAll(project.getRawModel().getBuild().getPlugins());
                    }
                    for (Profile p : profiles) {
                        if (p.getBuild() != null) {
                            rawPlugins.addAll(p.getBuild().getPlugins());
                        }
                    }
                }
            }
            return rawPlugins;
        }

        List<Plugin> getRawManagedPlugins() {
            if (rawManagedPlugins == null) {
                final List<Profile> profiles = getActivePomProfiles();
                if (profiles.isEmpty()) {
                    final PluginManagement pm = project.getRawModel().getBuild() == null ? null
                            : project.getRawModel().getBuild().getPluginManagement();
                    rawManagedPlugins = pm == null ? List.of() : pm.getPlugins();
                } else {
                    rawManagedPlugins = new ArrayList<>();
                    PluginManagement pm = project.getRawModel().getBuild() == null ? null
                            : project.getRawModel().getBuild().getPluginManagement();
                    if (pm != null) {
                        rawManagedPlugins.addAll(pm.getPlugins());
                    }
                    for (Profile p : profiles) {
                        pm = p.getBuild() == null ? null : p.getBuild().getPluginManagement();
                        if (pm != null) {
                            rawManagedPlugins.addAll(pm.getPlugins());
                        }
                    }
                }
            }
            return rawManagedPlugins;
        }

        private List<Profile> getActivePomProfiles() {
            if (activePomProfiles == null) {
                if (project.getModelBuildingResult() != null) {
                    var effectiveProfiles = project.getModelBuildingResult().getActivePomProfiles(
                            project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
                    if (effectiveProfiles.isEmpty()) {
                        activePomProfiles = List.of();
                    } else {
                        // re-parse the raw model to make sure the profiles are actually raw
                        final Model model;
                        try {
                            model = ModelUtils.readModel(getPomFile());
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to parse " + getPomFile(), e);
                        }
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

        private ConfiguredModule getConfiguredModule() {
            return module == null
                    ? module = ConfiguredModule
                            .of(WorkspaceModuleId.of(project.getGroupId(), project.getArtifactId(), project.getVersion()),
                                    project.getRawModel().getPomFile().toPath())
                    : module;
        }

        private ConfiguredArtifact getExtensionVersionConstraint(ArtifactKey key) {
            final ConfiguredArtifact configured = managedDeps.get(key);
            if (configured != null) {
                getConfiguredModule().addManagedExtension(configured);
            }
            return configured;
        }

        private ConfiguredArtifact getPluginVersionConstraint(ArtifactKey key) {
            if (quarkusPlugin != null && !quarkusPlugin.getVersion().isEffectivelyNull()) {
                return quarkusPlugin;
            }
            if (managedQuarkusPlugin != null) {
                return managedQuarkusPlugin;
            }
            final Plugin plugin = managedPlugins.get(key);
            if (plugin != null) {
                managedQuarkusPlugin = ConfiguredArtifact.jar(
                        ConfiguredValue.of(plugin.getGroupId(), plugin.getGroupId()),
                        ConfiguredValue.of(plugin.getArtifactId(), plugin.getArtifactId()),
                        ConfiguredValue.of(plugin.getVersion(), resolveValue(plugin.getVersion(), this)),
                        getPomFile());
                getConfiguredModule().setManagedQuarkusPlugin(managedQuarkusPlugin);
            }
            return managedQuarkusPlugin;
        }

        @Override
        public String getGroupId() {
            return project.getGroupId();
        }

        @Override
        public String getArtifactId() {
            return project.getArtifactId();
        }

        @Override
        public String getVersion() {
            return project.getVersion();
        }

        @Override
        public Properties getRawProperties() {
            if (rawProps == null) {
                final List<Profile> profiles = getActivePomProfiles();
                if (profiles.isEmpty()) {
                    rawProps = project.getRawModel().getProperties();
                } else {
                    rawProps = new Properties();
                    rawProps.putAll(project.getRawModel().getProperties());
                    for (Profile p : profiles) {
                        rawProps.putAll(p.getProperties());
                    }
                }
            }
            return rawProps;
        }

        @Override
        public PomWrapper getParentWrapper() {
            return getPomWrapper(project.getRawModel().getParent());
        }

        @Override
        public Path getPomFile() {
            return project.getRawModel().getPomFile().toPath();
        }
    }

    private class ModelWrapper implements PomWrapper {

        private final Model model;

        private ModelWrapper(Model model) {
            this.model = model;
        }

        @Override
        public String getGroupId() {
            return ModelUtils.getGroupId(model);
        }

        @Override
        public String getArtifactId() {
            return model.getArtifactId();
        }

        @Override
        public String getVersion() {
            return ModelUtils.getVersion(model);
        }

        @Override
        public Properties getRawProperties() {
            return model.getProperties();
        }

        @Override
        public PomWrapper getParentWrapper() {
            return getPomWrapper(model.getParent());
        }

        @Override
        public Path getPomFile() {
            return model.getPomFile().toPath();
        }
    }
}
