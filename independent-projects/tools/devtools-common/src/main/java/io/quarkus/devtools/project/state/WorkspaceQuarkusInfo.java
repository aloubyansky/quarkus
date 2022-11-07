package io.quarkus.devtools.project.state;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.devtools.project.update.ProjectUpdateInfos;
import io.quarkus.devtools.project.update.QuarkusPomUpdateRecipe;
import io.quarkus.devtools.project.update.QuarkusUpdateRunner;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.ExtensionCatalogResolver;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;

public class WorkspaceQuarkusInfo {

    public static void main(String[] args) throws Exception {
        //load(Path.of("/home/aloubyansky/git/kogito-runtimes/springboot/bom"));
        //MavenProjectConfigurationLoader.load(Path.of("/home/aloubyansky/git/debezium"));
        //MavenProjectConfigurationLoader.load(Path.of("/home/aloubyansky/git/optaplanner"));
        MavenProjectConfigurationLoader.load(Path.of("/home/aloubyansky/git/camel-quarkus"));
        //MavenProjectConfigurationLoader.load(Path.of("/home/aloubyansky/git/kogito-runtimes"));
        //MavenProjectConfigurationLoader.load(Path.of("/home/aloubyansky/git/kogito-examples"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/git/camel-quarkus"));
        //load(Path.of("/home/aloubyansky/git/quarkus-copy"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/git/quarkus-todo-app"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/playground/code-with-quarkus"));
        //load(Path.of("/home/aloubyansky/git/quarkus-todo-app/quarkus-todo-reactive"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/git/keycloak"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/git/cos-fleetshard"));
        //generateUpdateRecipe(Path.of("/home/aloubyansky/playground/code-with-quarkus"));
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
                recommendedOrigins = ProjectUpdateInfos.getRecommendedOrigins(latestCatalog, latestExtensions);
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
                    if (configuredBom.isImported()) {
                        buildFile = module.getBuildFile();
                        recipes.computeIfAbsent(buildFile, k -> QuarkusPomUpdateRecipe.generator().setPom(k))
                                .removeManagedDependency(configuredBom.getArtifact().getGroupId().getRawValue(),
                                        configuredBom.getArtifact().getArtifactId().getRawValue(), null);
                        removedFromBuildFile = buildFile;
                    } else {
                        System.out.println("REMOVE BOM IMPORT " + configuredBom);
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
