package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.devtools.commands.handlers.RegistryProjectInfo;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.maven.MavenConfiguredApplicationResolver;
import io.quarkus.devtools.project.configuration.maven.MavenProjectConfigurationLoader;
import io.quarkus.devtools.project.configuration.update.UpdateInstructions;
import io.quarkus.devtools.project.update.ProjectUpdateInfos;
import io.quarkus.devtools.project.update.QuarkusPomUpdateRecipe;
import io.quarkus.devtools.project.update.QuarkusUpdateRunner;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.ExtensionCatalogResolver;
import io.quarkus.registry.RegistryResolutionException;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;

public class WorkspaceQuarkusInfo {

    public static void main(String[] args) throws Exception {
        //loadAndLog(Path.of("/home/aloubyansky/playground/code-with-quarkus"));
        //MavenProjectConfigurationLoader.load(Path.of("/home/aloubyansky/git/insights-runtimes-inventory"));
        //loadAndLog(Path.of("/home/aloubyansky/git/hyades"));
        loadAndLog(Path.of("/home/aloubyansky/git/camel-quarkus"));
        //loadAndLog(Path.of("/home/aloubyansky/playground/quarkus-temporal-petstore"));
        //loadAndLog(Path.of("/home/aloubyansky/git/camel-quarkus/integration-tests/sql"));
        //loadAndLog(Path.of("/home/aloubyansky/git/quarkus-super-heroes/"));
        //loadAndLog(Path.of("/home/aloubyansky/git/quarkus-mcp-servers/"));
        //loadAndLog(Path.of("/home/aloubyansky/playground/quarkus-update-projects/parent-direct-extension-dep/app"));
        //load(Path.of("/home/aloubyansky/git/quarkus-todo-app/quarkus-todo-reactive"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/git/keycloak"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/playground/code-with-quarkus"));
    }

    private static ExtensionCatalogResolver registryClient(BootstrapMavenContext mvnCtx, MessageWriter log) throws Exception {
        return ExtensionCatalogResolver.builder()
                .artifactResolver(new MavenArtifactResolver(mvnCtx))
                .messageWriter(log)
                .build();
    }

    private static void loadAndLog(Path projectDir) throws Exception {
        var log = MessageWriter.info();
        var mavenCtx = MavenProjectConfigurationLoader.getBootstrapMavenContext(projectDir);
        //var project = MavenProjectConfigurationLoader.load(projectDir, mavenCtx, log);
        //logConfiguredProject(project, projectDir, log);
        //logInfo(project, mavenCtx, log);
        var apps = MavenConfiguredApplicationResolver.load(projectDir, mavenCtx, log);
        logConfiguredApps(apps, log);
        //logUpdateInstructions(apps, mavenCtx, log);
    }

    public static void logConfiguredApps(Collection<ConfiguredApplication> apps, MessageWriter log) {
        int i = 1;
        for (var app : apps) {
            log.info(i++ + ") Quarkus application " + app.getId());
            log.info("  Enforced platform BOMs:");
            for (var platformBom : app.getPlatformBoms()) {
                log.info("  - " + platformBom);
            }
            log.info("  Top extensions dependencies:");
            for (var topExtDep : app.getTopExtensionDependencies()) {
                log.info("  - " + topExtDep.toCompactString() + ", " + topExtDep.getConfigurationFile());
            }
            log.info("  Quarkus Maven plugin");
            log.info("  - " + app.getQuarkusPlugin().toCompactString());
        }
    }

    private static void logUpdateInstructions(Collection<ConfiguredApplication> apps, BootstrapMavenContext mavenCtx,
            MessageWriter log) {
        var extensionCatalog = getCatalogFromRegistry(apps, mavenCtx, log);
        var instructions = new UpdateInstructions();
        for (var app : apps) {
            addInstructions(instructions, app, extensionCatalog, log);
        }
        var instructionList = instructions.asList();
        if (instructionList.isEmpty()) {
            log.info("No updates recommended");
        } else {
            log.info("Recommended updates:");
            for (var instruction : instructionList) {
                log.info("  " + instruction.toString());
            }
        }
    }

    public static void addInstructions(UpdateInstructions instructions, ConfiguredApplication app,
            ExtensionCatalog extensionCatalog, MessageWriter log) {
        var extDeps = getTopExtensionMap(app);
        final RegistryProjectInfo registryInfo;
        try {
            registryInfo = RegistryProjectInfo.fromCatalog(extensionCatalog, extDeps.values());
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to determine recommended updates for " + app.getId() + " in " + app.getModuleConfigFile(), e);
        }
        UpdateInstructions.add(instructions, app, registryInfo, log);
    }

    private static ExtensionCatalog getCatalogFromRegistry(Collection<ConfiguredApplication> apps,
            BootstrapMavenContext mavenCtx, MessageWriter log) {
        var startTime = System.currentTimeMillis();
        final ExtensionCatalogResolver registryClient;
        try {
            registryClient = registryClient(mavenCtx, log);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize the registry client", e);
        }
        log.info("Registry client initialized in " + (System.currentTimeMillis() - startTime));
        //var extensionCatalog = registryClient.resolveExtensionCatalog(toArtifactCoordsList(quarkusApp.getPlatformBoms()));
        final ExtensionCatalog extensionCatalog;
        try {
            extensionCatalog = registryClient.resolveExtensionCatalog();
        } catch (RegistryResolutionException e) {
            throw new RuntimeException("Failed to resolve extension catalog", e);
        }
        log.info("Resolved catalog in " + (System.currentTimeMillis() - startTime));
        return extensionCatalog;
    }

    private static Map<ArtifactKey, ConfiguredArtifact> getTopExtensionMap(ConfiguredApplication quarkusApp) {
        final List<ConfiguredArtifact> exts = quarkusApp.getTopExtensionDependencies();
        final Map<ArtifactKey, ConfiguredArtifact> topExtensions = new HashMap<>(exts.size());
        for (var e : exts) {
            topExtensions.put(e.getKey(), e);
        }
        return topExtensions;
    }

    private static List<ArtifactCoords> toArtifactCoordsList(Collection<ConfiguredBom> platformBoms) {
        var result = new ArrayList<ArtifactCoords>(platformBoms.size());
        for (var platformBom : platformBoms) {
            result.add(platformBom.getArtifact().getEffectiveCoords());
        }
        return result;
    }

    private static ConfiguredModule getFirstQuarkusApp(ConfiguredProject project) {
        for (var module : project.getModules()) {
            if (module.isQuarkusApplication()) {
                return module;
            }
        }
        throw new RuntimeException("Failed to locate a Quarkus application in the project");
    }

    private static void logConfiguredProject(ConfiguredProject project, Path projectDir, MessageWriter log) {
        for (var module : project.getModules()) {
            if (module != null && isLog(module) && module.getBuildFile().startsWith(projectDir)) {
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

    public static void generateUpdateRecipe(Path projectDir) throws Exception {
        final BootstrapMavenContext mavenCtx = new BootstrapMavenContext(
                BootstrapMavenContext.config()
                        .setCurrentProject(projectDir.toString())
                        .setEffectiveModelBuilder(true)
                        .setPreferPomsFromWorkspace(true));

        final ConfiguredProject project = MavenProjectConfigurationLoader.load(projectDir, mavenCtx);

        final ExtensionCatalog latestCatalog = ExtensionCatalogResolver.builder()
                .artifactResolver(new MavenArtifactResolver(mavenCtx)).build().resolveExtensionCatalog();
        final Map<ArtifactKey, Extension> latestMap = new HashMap<>(latestCatalog.getExtensions().size());
        latestCatalog.getExtensions().forEach(e -> latestMap.put(e.getArtifact().getKey(), e));

        final Map<Path, QuarkusPomUpdateRecipe.Generator> recipes = new HashMap<>();
        for (ConfiguredModule module : project.getModules()) {
            if (!module.isQuarkusApplication()) {
                continue;
            }

            List<ConfiguredArtifact> unavailableExtensions = new ArrayList<>(0);
            List<Extension> latestExtensions = new ArrayList<>(module.getDirectExtensionDeps().size());
            for (ConfiguredArtifact a : module.getDirectExtensionDeps()) {
                if (a.isLocal()) {
                    continue;
                }
                final Extension latestExtension = latestMap.get(a.getKey());
                if (latestExtension == null) {
                    unavailableExtensions.add(a);
                } else {
                    latestExtensions.add(latestExtension);
                }
            }

            if (!unavailableExtensions.isEmpty()) {
                log("WARN: the configured Quarkus registries did not provide any compatibility information for the following extensions in the context of the currently recommended Quarkus platforms:");
                unavailableExtensions.forEach(e -> System.out.println("- " + e.getEffectiveCoords()));
            }

            List<ExtensionCatalog> recommendedOrigins = List.of();
            Map<String, ExtensionCatalog> recommendedPlatforms = new HashMap<>(0);
            ArtifactCoords recommendedCoreBom = null;
            if (!latestExtensions.isEmpty()) {
                recommendedOrigins = ProjectUpdateInfos.getRecommendedOrigins(latestExtensions);
                recommendedPlatforms = new HashMap<>();
                for (ExtensionCatalog c : recommendedOrigins) {
                    if (c.isPlatform()) {
                        recommendedPlatforms.put(c.getBom().getArtifactId(), c);
                        if (c.getBom().getArtifactId().equals("quarkus-bom")) {
                            recommendedCoreBom = c.getBom();
                        }
                    }
                }
            }

            // BOM UPDATES
            Path removedFromBuildFile = null;
            for (ConfiguredBom configuredBom : module.getPlatformBoms()) {
                final ArtifactCoords currentBom = configuredBom.getArtifact().getEffectiveCoords();
                final ExtensionCatalog recommendedPlatform = recommendedPlatforms.remove(currentBom.getArtifactId());
                if (recommendedPlatform == null) {
                    final Path buildFile;
                    if (configuredBom.isEnforcedExternally()) {
                        System.out.println("REMOVE BOM IMPORT " + configuredBom);
                    } else {
                        buildFile = module.getBuildFile();
                        recipes.computeIfAbsent(buildFile, k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                .removeManagedDependency(configuredBom.getArtifact().getGroupId().getRawValue(),
                                        configuredBom.getArtifact().getArtifactId().getRawValue(), null);
                        removedFromBuildFile = buildFile;
                    }
                    continue;
                }
                final ArtifactCoords recommendedBom = recommendedPlatform.getBom();
                if (!recommendedBom.equals(currentBom)) {
                    String newGroupId = null;
                    String newVersion = null;
                    if (!recommendedBom.getGroupId().equals(currentBom.getGroupId())) {
                        if (configuredBom.getArtifact().getGroupId().isProperty()) {
                            final ValueSource groupIdSrc = configuredBom.getArtifact().getGroupId().getResolvedValue()
                                    .getSource();
                            recipes.computeIfAbsent(groupIdSrc.getPath(), k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                    .updateProperty(groupIdSrc.getProperty(), recommendedBom.getGroupId());
                        } else {
                            newGroupId = recommendedBom.getGroupId();
                        }
                    }
                    if (!recommendedBom.getVersion().equals(currentBom.getVersion())) {
                        if (configuredBom.getArtifact().getVersion().isProperty()) {
                            final ValueSource versionSrc = configuredBom.getArtifact().getVersion().getResolvedValue()
                                    .getSource();
                            recipes.computeIfAbsent(versionSrc.getPath(), k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                    .updateProperty(versionSrc.getProperty(), recommendedBom.getVersion());
                        } else {
                            newVersion = recommendedBom.getVersion();
                        }
                    }
                    if (newGroupId != null || newVersion != null) {
                        final String currentGroupId = configuredBom.getArtifact().getGroupId().getRawValue();
                        final String currentArtifactId = configuredBom.getArtifact().getArtifactId().getRawValue();
                        recipes.computeIfAbsent(module.getBuildFile(), k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                .updateManagedDependency(currentGroupId, currentArtifactId,
                                        newGroupId == null ? currentGroupId : newGroupId, currentArtifactId, newVersion);
                    }
                }
            }

            // NEW BOM IMPORTS
            for (ExtensionCatalog newImport : recommendedPlatforms.values()) {
                Path buildFile = module.getBuildFile();
                if (removedFromBuildFile != null) {
                    buildFile = removedFromBuildFile;
                }
                final ArtifactCoords bom = newImport.getBom();
                recipes.computeIfAbsent(buildFile, k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                        .addManagedDependency(bom.getGroupId(), bom.getArtifactId(), bom.getClassifier(), bom.getType(),
                                bom.getVersion(), "import");
            }

            // PLUGIN UPDATES
            if (recommendedCoreBom != null && module.getQuarkusPlugin() != null) {
                final ConfiguredArtifact configuredPlugin = module.getQuarkusPlugin();
                if (!recommendedCoreBom.getGroupId().equals(configuredPlugin.getGroupId().getEffectiveValue())) {
                    if (configuredPlugin.getGroupId().isProperty()) {
                        final ValueSource groupIdSrc = configuredPlugin.getGroupId().getResolvedValue().getSource();
                        recipes.computeIfAbsent(groupIdSrc.getPath(), k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                .updateProperty(groupIdSrc.getProperty(), recommendedCoreBom.getGroupId());
                    } else {
                        // TODO
                        System.out.println(
                                "UPDATE PLUGIN GROUP-ID " + configuredPlugin + " to be in line with " + recommendedCoreBom);
                    }
                }
                if (!recommendedCoreBom.getVersion().equals(configuredPlugin.getVersion().getEffectiveValue())) {
                    if (configuredPlugin.getVersion().isProperty()) {
                        final ValueSource versionSrc = configuredPlugin.getVersion().getResolvedValue().getSource();
                        recipes.computeIfAbsent(versionSrc.getPath(), k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                .updateProperty(versionSrc.getProperty(), recommendedCoreBom.getVersion());
                    } else {
                        final Path buildFile = configuredPlugin.getVersion().getResolvedValue().getSource().getPath();
                        if (buildFile == null) {
                            System.out.println(
                                    "WARN: could not update plugin version because the target build file is not known");
                        } else {
                            recipes.computeIfAbsent(buildFile, k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                    .updatePluginVersion(configuredPlugin.getGroupId().getEffectiveValue(),
                                            configuredPlugin.getArtifactId().getEffectiveValue(),
                                            recommendedCoreBom.getVersion());
                        }
                    }
                }
            }

            if (!latestExtensions.isEmpty()) {
                final Map<ArtifactKey, ArtifactCoords> nonPlatformOrigins = new HashMap<>(latestCatalog.getExtensions().size());
                for (ExtensionCatalog c : recommendedOrigins) {
                    if (!c.isPlatform()) {
                        c.getExtensions()
                                .forEach(e -> nonPlatformOrigins.putIfAbsent(e.getArtifact().getKey(), e.getArtifact()));
                    }
                }

                if (!nonPlatformOrigins.isEmpty()) {
                    for (ConfiguredArtifact configuredExt : module.getDirectExtensionDeps()) {
                        final ArtifactCoords recommendedCoords = nonPlatformOrigins
                                .remove(configuredExt.getEffectiveCoords().getKey());
                        if (recommendedCoords != null
                                && !configuredExt.getVersion().getEffectiveValue().equals(recommendedCoords.getVersion())) {
                            if (configuredExt.getVersion().isProperty()) {
                                final ValueSource versionSrc = configuredExt.getVersion().getResolvedValue().getSource();
                                recipes.computeIfAbsent(versionSrc.getPath(), k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                        .updateProperty(versionSrc.getProperty(), recommendedCoords.getVersion());
                            } else {
                                recipes.computeIfAbsent(module.getBuildFile(),
                                        k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                        .updateDependency(configuredExt.getGroupId().getRawValue(),
                                                configuredExt.getArtifactId().getRawValue(), null, null,
                                                recommendedCoords.getVersion());
                            }
                        }
                    }
                }
            }
        }

        log("Applying " + recipes.size() + " update recipe(s)");
        recipes.values().forEach(g -> {
            QuarkusUpdateRunner.applyDirectly(g.generate(), true);
        });
        log("Done!");
    }

    private static void log(Object o) {
        System.out.println(o == null ? "null" : o.toString());
    }
}
