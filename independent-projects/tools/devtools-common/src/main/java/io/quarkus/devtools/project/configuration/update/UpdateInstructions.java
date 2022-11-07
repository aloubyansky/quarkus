package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.quarkus.devtools.commands.handlers.RegistryProjectInfo;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;

public class UpdateInstructions {

    private static final String MANAGED_VERSION = "MANAGED_VERSION";

    /**
     * Provides a list of steps that would align the current project with the registry recommendations.
     *
     * @param app current project
     * @param registryProjectInfo registry recommendation
     * @return update steps, never null
     */
    public static UpdateInstructions get(ConfiguredApplication app, RegistryProjectInfo registryProjectInfo,
            MessageWriter log) {
        var allUpdateSteps = new UpdateInstructions();
        add(allUpdateSteps, app, registryProjectInfo, log);
        return allUpdateSteps;
    }

    public static void add(UpdateInstructions instructions,
            ConfiguredApplication app,
            RegistryProjectInfo registryProjectInfo,
            MessageWriter log) {
        new BomMapper(app, registryProjectInfo, log).add(instructions);
        addDependencyInstructions(app, registryProjectInfo, instructions, log);
        addPluginInstructions(app, registryProjectInfo, instructions);
    }

    private static void addPluginInstructions(ConfiguredApplication app, RegistryProjectInfo registryProjectInfo,
            UpdateInstructions updateSteps) {
        var configuredPlugin = app.getQuarkusPlugin();
        var recommendedPlugin = registryProjectInfo.getQuarkusMavenPlugin();

        final ConfiguredValue specificGroupId = updatePropertyOrReturnConfiguredValue(updateSteps,
                recommendedPlugin.getGroupId(), configuredPlugin.getGroupId());
        final ConfiguredValue specificArtifactId = updatePropertyOrReturnConfiguredValue(updateSteps,
                recommendedPlugin.getArtifactId(), configuredPlugin.getArtifactId());
        final ConfiguredValue specificVersion = updatePropertyOrReturnConfiguredValue(updateSteps,
                recommendedPlugin.getVersion(), configuredPlugin.getVersion());

        if (specificGroupId != null || specificArtifactId != null || specificVersion != null) {
            final Map<Path, MavenPluginConfig> pluginConfigs = new HashMap<>(3);
            if (specificGroupId != null) {
                pluginConfigs.computeIfAbsent(specificGroupId.getResolvedValue().getSource().getPath(),
                        path -> new MavenPluginConfig(path, configuredPlugin))
                        .setRecommendedGroupId(recommendedPlugin.getGroupId());
            }
            if (specificArtifactId != null) {
                pluginConfigs.computeIfAbsent(specificArtifactId.getResolvedValue().getSource().getPath(),
                        path -> new MavenPluginConfig(path, configuredPlugin))
                        .setRecommendedArtifactId(recommendedPlugin.getArtifactId());
            }
            if (specificVersion != null) {
                pluginConfigs.computeIfAbsent(specificVersion.getResolvedValue().getSource().getPath(),
                        path -> new MavenPluginConfig(path, configuredPlugin))
                        .setRecommendedVersion(recommendedPlugin.getVersion());
            }
            for (var pluginConfig : pluginConfigs.values()) {
                pluginConfig.addUpdateStep(updateSteps);
            }
        }
    }

    private static ConfiguredValue updatePropertyOrReturnConfiguredValue(UpdateInstructions updateSteps,
            String recommendedValue,
            ConfiguredValue configuredValue) {
        if (!recommendedValue.equals(configuredValue.getEffectiveValue())) {
            if (configuredValue.getResolvedValue().getSource().isProperty()) {
                updateSteps.add(PomPropertyInstruction.update(configuredValue.getResolvedValue(), recommendedValue));
            } else {
                return configuredValue;
            }
        }
        return null;
    }

    private static void addDependencyInstructions(ConfiguredApplication app, RegistryProjectInfo registryProjectInfo,
            UpdateInstructions updateSteps, MessageWriter log) {
        final Map<ArtifactKey, String> recommendedExtensions = getRecommendedExtensionVersions(registryProjectInfo);
        for (var configuredExt : app.getTopExtensionDependencies()) {
            final String recommendedVersion = recommendedExtensions.remove(configuredExt.getKey());
            if (recommendedVersion == null) {
                if (registryProjectInfo.isNotAvailable(configuredExt.getEffectiveCoords().getKey())) {
                    log.warn("Extension " + configuredExt.getEffectiveCoords().toCompactCoords() + " used in "
                            + app.getId().getGroupId() + ":" + app.getId().getArtifactId()
                            + " is not present in the extension catalog for the proposed Quarkus version and will not be updated");
                } else {
                    updateSteps
                            .add(DependencyInstruction.remove(configuredExt.getConfigurationFile(),
                                    configuredExt.getRawCoords()));
                }
            } else if (MANAGED_VERSION.equals(recommendedVersion)) {
                if (configuredExt.getVersion().getRawValue() != null) {
                    final ArtifactCoords rawCoords = configuredExt.getRawCoords();
                    updateSteps.add(DependencyInstruction.update(configuredExt.getConfigurationFile(), rawCoords,
                            ArtifactCoords.of(rawCoords.getGroupId(), rawCoords.getArtifactId(), rawCoords.getClassifier(),
                                    rawCoords.getType(), null)));
                }
            } else if (!recommendedVersion.equals(configuredExt.getVersion().getEffectiveValue())) {
                var configuredVersionValue = configuredExt.getVersion().getResolvedValue();
                if (configuredVersionValue.getSource().isProperty()) {
                    updateSteps.add(PomPropertyInstruction.update(configuredVersionValue, recommendedVersion));
                } else {
                    final ArtifactCoords rawCoords = configuredExt.getRawCoords();
                    updateSteps.add(DependencyInstruction.update(configuredExt.getConfigurationFile(), rawCoords,
                            ArtifactCoords.of(rawCoords.getGroupId(), rawCoords.getArtifactId(), rawCoords.getClassifier(),
                                    rawCoords.getType(), recommendedVersion)));
                }
            }
        }

        if (!recommendedExtensions.isEmpty()) {
            for (var ext : recommendedExtensions.entrySet()) {
                updateSteps
                        .add(DependencyInstruction.add(app.getModuleConfigFile(), new GACTV(ext.getKey(), ext.getValue())));
            }
        }
    }

    private static Map<ArtifactKey, String> getRecommendedExtensionVersions(RegistryProjectInfo registryProjectInfo) {
        final Collection<String> recommendedPlatformBomIds = getRecommendedPlatformBomIds(registryProjectInfo);
        final List<Extension> registryExtensions = registryProjectInfo.getExtensions();
        final Map<ArtifactKey, String> recommendedExtensions = new HashMap<>(registryExtensions.size());
        for (var ext : registryExtensions) {
            recommendedExtensions.put(ext.getArtifact().getKey(), isExtensionManaged(ext, recommendedPlatformBomIds)
                    ? MANAGED_VERSION
                    : ext.getArtifact().getVersion());
        }
        return recommendedExtensions;
    }

    private static boolean isExtensionManaged(Extension ext, Collection<String> platformBomIds) {
        for (var origin : ext.getOrigins()) {
            if (platformBomIds.contains(origin.getId())) {
                return true;
            }
        }
        return false;
    }

    private static Collection<String> getRecommendedPlatformBomIds(RegistryProjectInfo registryProjectInfo) {
        final List<ExtensionCatalog> extOrigins = registryProjectInfo.getExtensionOrigins();
        final int size = extOrigins.size();
        final Collection<String> recommendedPlatformBomIds = size < 3 ? new ArrayList<>(size) : new HashSet<>(size);
        for (var origin : extOrigins) {
            if (origin.isPlatform()) {
                recommendedPlatformBomIds.add(origin.getId());
            }
        }
        return recommendedPlatformBomIds;
    }

    private List<UpdateInstruction> instructions = new ArrayList<>();

    public UpdateInstructions() {
    }

    void add(UpdateInstruction instruction) {
        boolean skip = false;
        for (int i = 0; i < instructions.size(); ++i) {
            var existing = instructions.get(i);
            switch (existing.compareTo(instruction)) {
                case SUPERSEDED:
                    instructions.set(i, instruction);
                    break;
                case MATCHES:
                case SUPERSEDES:
                    skip = true;
                    break;
                case CONFLICTS:
                    throw new RuntimeException("Instruction " + existing + " conflicts with " + instruction);
                case COMPATIBLE:
                    // continue;
            }
        }
        if (!skip) {
            instructions.add(instruction);
        }
    }

    public List<UpdateInstruction> asList() {
        return instructions;
    }

    public List<FileUpdateInstructions> asFileInstructions() {
        if (instructions.isEmpty()) {
            return List.of();
        }
        if (instructions.size() == 1) {
            var i = instructions.get(0);
            return List.of(FileUpdateInstructions.builder().setFile(i.getFile()).add(i).build());
        }
        final TreeMap<String, FileUpdateInstructions.Builder> builders = new TreeMap<>();
        for (var i : instructions) {
            builders.computeIfAbsent(i.getFile().toString(), k -> FileUpdateInstructions.builder().setFile(i.getFile())).add(i);
        }
        final List<FileUpdateInstructions> result = new ArrayList<>(builders.size());
        for (var builder : builders.values()) {
            result.add(builder.build());
        }
        return result;
    }
}
