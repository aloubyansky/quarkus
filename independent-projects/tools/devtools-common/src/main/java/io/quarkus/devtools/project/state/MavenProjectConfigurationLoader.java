package io.quarkus.devtools.project.state;

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
import java.util.stream.Collectors;

import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
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
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.PathTree;
import io.quarkus.registry.util.PlatformArtifacts;

public class MavenProjectConfigurationLoader {

    public static ConfiguredProject load(Path projectDir) throws Exception {
        return load(projectDir, new BootstrapMavenContext(
                BootstrapMavenContext.config()
                        .setCurrentProject(projectDir.toString())
                        .setEffectiveModelBuilder(true)
                        .setPreferPomsFromWorkspace(true)),
                MessageWriter.info());
    }

    public static ConfiguredProject load(Path projectDir, BootstrapMavenContext mavenCtx) throws Exception {
        return load(projectDir, mavenCtx, MessageWriter.info());
    }

    public static ConfiguredProject load(Path projectDir, BootstrapMavenContext mavenCtx, MessageWriter log) throws Exception {
        return new MavenProjectConfigurationLoader(mavenCtx, log).loadInternal(projectDir);
    }

    private final Map<ArtifactKey, ModuleWrapper> modules = new HashMap<>();
    private final Map<ArtifactCoords, Map<ArtifactKey, String>> externalBoms = new HashMap<>();
    private final BootstrapMavenContext mavenContext;
    private final MessageWriter log;

    private MavenProjectConfigurationLoader(BootstrapMavenContext mavenContext, MessageWriter log) {
        this.mavenContext = mavenContext;
        this.log = log;
    }

    private ConfiguredProject loadInternal(Path projectDir) throws Exception {

        log.debug("Loading project configuration for %s", projectDir);
        final long start = System.currentTimeMillis();

        final List<Path> createdDirs = new ArrayList<>();
        final LocalWorkspace ws = mavenContext.getWorkspace();
        ws.getProjects().values().forEach(p -> ensureResolvable(p, createdDirs));
        try {
            for (LocalProject project : ws.getProjects().values()) {
                final ModuleWrapper moduleWrapper = getModuleWrapper(project);
                if (!moduleWrapper.effectiveDirectDeps.isEmpty()) {
                    final Map<ArtifactKey, ConfiguredArtifact> rawDeps = new HashMap<>(
                            moduleWrapper.getRawDirectDeps().size());
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
                                    !version.isEffectivelyNull()
                                            && isLocal(groupId.getEffectiveValue(), artifactId.getEffectiveValue(),
                                                    version.getEffectiveValue()));
                            rawDeps.put(c.getKey(), c);
                        }
                    }

                    final List<ArtifactRequest> requests = new ArrayList<>();
                    for (Dependency d : moduleWrapper.effectiveDirectDeps) {
                        final Artifact artifact = d.getArtifact();
                        if (isPotentialExtension(artifact.getExtension(), artifact.getClassifier())) {
                            requests.add(new ArtifactRequest()
                                    .setArtifact(artifact)
                                    .setRepositories(mavenContext.getRepositorySystem().newResolutionRepositories(
                                            mavenContext.getRepositorySystemSession(), moduleWrapper.effectiveRepos)));
                        }
                    }
                    final List<ArtifactResult> results;
                    try {
                        results = mavenContext.getRepositorySystem()
                                .resolveArtifacts(mavenContext.getRepositorySystemSession(), requests);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to process " + project.getDir(), e);
                    }
                    for (ArtifactResult r : results) {
                        PathTree.ofDirectoryOrArchive(r.getArtifact().getFile().toPath())
                                .accept(BootstrapConstants.DESCRIPTOR_PATH, visit -> {
                                    if (visit == null) {
                                        return;
                                    }
                                    final Artifact a = r.getArtifact();
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
                                                    isLocal(a.getGroupId(), a.getArtifactId(), a.getVersion()));
                                        }
                                        moduleWrapper.getConfiguredModule().addDirectExtensionDep(c);
                                    } else {
                                        c = ConfiguredArtifact.of(ConfiguredValue.of(a.getGroupId()),
                                                ConfiguredValue.of(a.getArtifactId()),
                                                ConfiguredValue.of(a.getClassifier()),
                                                ConfiguredValue.of(a.getExtension()),
                                                ConfiguredValue.of(null, getInheritedDependencyVersion(moduleWrapper, a)),
                                                isLocal(a.getGroupId(), a.getArtifactId(), a.getVersion()));
                                        moduleWrapper.getConfiguredModule().addTopTransitiveExtensionDep(c);
                                    }
                                });
                    }
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
                                    ConfiguredValue.of(plugin.getVersion().getRawValue(), managedVersion));
                            moduleWrapper.setQuarkusPlugin(plugin);
                        }
                    }
                }
            }

            final ConfiguredProject project = new ConfiguredProject(projectDir);
            for (ModuleWrapper md : modules.values()) {
                final ConfiguredModule module = md.module;
                if (module != null && isLog(module) && md.getPomFile().startsWith(projectDir)) {
                    project.addModule(module);
                    log.info("Module " + module.getId());
                    if (module.isQuarkusApplication()) {
                        log.info("  Quarkus application");
                    }
                    for (ConfiguredBom bom : module.getPlatformBoms()) {
                        final StringBuilder sb = new StringBuilder();
                        sb.append("  ").append(bom);
                        if (!bom.getArtifact().isLocal()) {
                            ResolvedValue value = bom.getArtifact().getArtifactId().getResolvedValue();
                            ValueSource src = value == null ? null : value.getSource();
                            if (src != null && src.getModule() != null) {
                                sb.append(" in ").append(src.getModule());
                            }
                        }
                        log.info(sb.toString());
                    }
                    if (module.getQuarkusPlugin() != null) {
                        log.info("  Quarkus plugin: " + module.getQuarkusPlugin().toCompactString());
                    }
                    if (module.getManagedQuarkusPlugin() != null) {
                        log.info("  Managed Quarkus plugin: " + module.getManagedQuarkusPlugin().toCompactString());
                    }
                    if (!module.getManagedExtensions().isEmpty()) {
                        log.info("  Extension version constraints:");
                        module.getManagedExtensions().forEach(d -> log.info("  - " + d.toCompactString()));
                    }
                    if (!module.getDirectExtensionDeps().isEmpty()) {
                        log.info("  Extension dependencies:");
                        module.getDirectExtensionDeps().forEach(d -> log.info("  - " + d.toCompactString()));
                    }
                    if (!module.getTopTransitiveExtensionDeps().isEmpty()) {
                        log.info("  Top transitive extension dependencies:");
                        module.getTopTransitiveExtensionDeps().forEach(d -> log.info("  - " + d.toCompactString()));
                    }
                }
            }
            return project;
        } finally {
            for (Path p : createdDirs) {
                IoUtils.recursiveDelete(p);
            }
            log.info("Done in " + (System.currentTimeMillis() - start));
        }
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

    private static boolean isLog(ConfiguredModule module) {
        if (module.isQuarkusApplication()) {
            return true;
        }
        /* @formatter:off
        for (ConfiguredArtifact a : module.getDirectExtensionDeps()) {
            if (a.getVersion().getRawValue() != null) {
                return true;
            }
        }
        for (ConfiguredArtifact a : module.getManagedExtensions()) {
            if (a.getVersion().getRawValue() != null) {
                return true;
            }
        }
        if (module.getManagedQuarkusPlugin() != null && module.getManagedQuarkusPlugin().getVersion().getRawValue() != null) {
            return true;
        }
        if (module.getQuarkusPlugin() != null && module.getQuarkusPlugin().getVersion().getRawValue() != null) {
            return true;
        }
        for (ConfiguredBom bom : module.getPlatformBoms()) {
            if (bom.isImported()) {
                return true;
            }
        }
        @formatter:on */
        return false;
    }

    private boolean isLocal(String groupId, String artifactId, String version) {
        final LocalProject project = mavenContext.getWorkspace().getProject(groupId, artifactId);
        return project != null && project.getVersion().equals(version);
    }

    private ModuleWrapper getModuleWrapper(LocalProject project) {
        ModuleWrapper module = this.modules.get(project.getKey());
        if (module == null) {
            List<Dependency> effectiveManagedDeps = List.of();
            List<Dependency> effectiveDirectDeps = List.of();
            List<RemoteRepository> effectiveRepos = List.of();
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

    private ResolvedValue getManagedPluginVersion(ModuleWrapper moduleDeps, ArtifactKey pluginKey) throws Exception {
        final ConfiguredArtifact configured = moduleDeps.getPluginVersionConstraint(pluginKey);
        if (configured != null) {
            return configured.getVersion().getResolvedValue();
        }
        final LocalProject parent = moduleDeps.project.getLocalParent();
        if (parent != null) {
            return getManagedPluginVersion(getModuleWrapper(parent), pluginKey);
        }
        return null;
    }

    private void collectManagedDeps(ModuleWrapper moduleWrapper) {

        if (!moduleWrapper.getRawManagedDeps().isEmpty()) {
            moduleWrapper.getRawManagedDeps().forEach(d -> {
                final ConfiguredValue groupId = ConfiguredValue.of(d.getGroupId(), resolveValue(d.getGroupId(), moduleWrapper));
                final ConfiguredValue artifactId = ConfiguredValue.of(d.getArtifactId(),
                        resolveValue(d.getArtifactId(), moduleWrapper));
                final ConfiguredValue version = ConfiguredValue.of(d.getVersion(), resolveValue(d.getVersion(), moduleWrapper));
                if ("import".equals(d.getScope())) {
                    final ArtifactCoords effectiveCoords = ArtifactCoords.pom(groupId.getEffectiveValue(),
                            artifactId.getEffectiveValue(), version.getEffectiveValue());
                    moduleWrapper.bomImports.put(effectiveCoords,
                            ConfiguredBom.imported(groupId, artifactId, version,
                                    isLocal(groupId.getEffectiveValue(), artifactId.getEffectiveValue(),
                                            version.getEffectiveValue())));
                } else {
                    final String classifier = d.getClassifier() == null ? ArtifactCoords.DEFAULT_CLASSIFIER : d.getClassifier();
                    final ConfiguredArtifact artifact = ConfiguredArtifact.of(
                            groupId, artifactId,
                            ConfiguredValue.of(classifier, resolveValue(classifier, moduleWrapper)),
                            ConfiguredValue.of(d.getType(), resolveValue(d.getType(), moduleWrapper)),
                            version,
                            isLocal(groupId.getEffectiveValue(), artifactId.getEffectiveValue(), version.getEffectiveValue()));
                    moduleWrapper.managedDeps.put(artifact.getKey(), artifact);
                }
            });
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
                        ConfiguredValue.of(rawPlugin.getVersion(), resolveValue(rawPlugin.getVersion(), moduleWrapper))));
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

    private ResolvedValue resolveValue(String expr, String wrappingExpr, PomWrapper rawModel) {

        if (!ConfiguredValue.isPropertyExpression(expr)) {
            final LocalProject project = mavenContext.getWorkspace().getProject(rawModel.getGroupId(),
                    rawModel.getArtifactId());
            final ValueSource source = project == null
                    ? ValueSource.external(rawModel.getId(), wrappingExpr, rawModel.getPomFile())
                    : ValueSource.local(rawModel.getId(), wrappingExpr, rawModel.getPomFile());
            return ResolvedValue.of(expr, source);
        }
        final String name = ConfiguredValue.getPropertyName(expr);
        if (name.startsWith("project.")) {
            final String projectProp = name.substring("project.".length());
            switch (projectProp) {
                case "version":
                    return ResolvedValue.of(rawModel.getVersion(),
                            ValueSource.local(rawModel.getId(), name, rawModel.getPomFile()));
                case "groupId":
                    return ResolvedValue.of(rawModel.getGroupId(),
                            ValueSource.local(rawModel.getId(), name, rawModel.getPomFile()));
                case "artifactId":
                    return ResolvedValue.of(rawModel.getArtifactId(),
                            ValueSource.local(rawModel.getId(), name, rawModel.getPomFile()));
            }
        }
        final String value = rawModel.getRawProperties().getProperty(name);
        if (value != null) {
            return resolveValue(value, name, rawModel);
        }
        final PomWrapper parent = rawModel.getParentWrapper();
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
        final Model parentModel;
        try {
            parentModel = ModelUtils.readModel(pomXml.toPath());
            parentModel.setPomFile(pomXml);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + pomXml, e);
        }
        return new ModelWrapper(parentModel);
    }

    private static Collection<ArtifactCoords> getEnforcedPlatformBoms(List<Dependency> managedDeps) {
        final List<ArtifactCoords> enforcedPlatformBoms = new ArrayList<>(3);
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
            final Path classesDir = project.getClassesDir();
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
                        var activeIds = effectiveProfiles.stream().map(Profile::getId).collect(Collectors.toList());
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
            if (plugin == null) {
                return null;
            }
            managedQuarkusPlugin = ConfiguredArtifact.jar(
                    ConfiguredValue.of(plugin.getGroupId(), plugin.getGroupId()),
                    ConfiguredValue.of(plugin.getArtifactId(), plugin.getArtifactId()),
                    ConfiguredValue.of(plugin.getVersion(), resolveValue(plugin.getVersion(), this)));
            getConfiguredModule().setManagedQuarkusPlugin(managedQuarkusPlugin);
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
