package io.quarkus.devtools.project.configuration.step;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import io.quarkus.devtools.commands.handlers.RegistryProjectInfo;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.devtools.project.configuration.ConfiguredValue;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;

public class UpdateSteps {

    private static final String MANAGED_VERSION = "MANAGED_VERSION";

    /**
     * Provides a list of steps that would align the current project with the registry recommendations.
     *
     * @param app current project
     * @param registryProjectInfo registry recommendation
     * @return update steps, never null
     */
    public static UpdateSteps getUpdateSteps(ConfiguredApplication app, RegistryProjectInfo registryProjectInfo) {
        var allUpdateSteps = new UpdateSteps();
        addUpdateSteps(app, registryProjectInfo, allUpdateSteps);
        return allUpdateSteps;
    }

    public static void addUpdateSteps(ConfiguredApplication app, RegistryProjectInfo registryProjectInfo,
            UpdateSteps updateSteps) {
        new BomMapper(app, registryProjectInfo).addSteps(updateSteps);
        addDependencyUpdateSteps(app, registryProjectInfo, updateSteps);

        System.out.println("Project Maven plugin " + app.getQuarkusPlugin());
        System.out.println("Registry Maven plugin " + registryProjectInfo.getQuarkusMavenPlugin());

        var configuredPlugin = app.getQuarkusPlugin();
        var recommendedPlugin = registryProjectInfo.getQuarkusMavenPlugin();

        final ConfiguredValue specificGroupId = updatePropertyOrReturnConfiguredValue(updateSteps, recommendedPlugin.getGroupId(), configuredPlugin.getGroupId());
        final ConfiguredValue specificArtifactId = updatePropertyOrReturnConfiguredValue(updateSteps, recommendedPlugin.getArtifactId(), configuredPlugin.getArtifactId());
        final ConfiguredValue specificVersion = updatePropertyOrReturnConfiguredValue(updateSteps, recommendedPlugin.getVersion(), configuredPlugin.getVersion());

        if(specificGroupId != null || specificArtifactId != null || specificVersion != null) {
            // TODO set specific plugin values
            System.out.println("Update Maven plugin:");
            if(specificGroupId != null) {
                System.out.println("  groupId: " + specificGroupId + " -> " + recommendedPlugin.getGroupId() + ", file=" + specificGroupId.getResolvedValue().getSource().getPath());
            }
            if(specificArtifactId != null) {
                System.out.println("  artifactId: " + specificArtifactId + " -> " + recommendedPlugin.getArtifactId() + ", file=" + specificArtifactId.getResolvedValue().getSource().getPath());
            }
            if(specificVersion != null) {
                System.out.println("  version: " + specificVersion + " -> " + recommendedPlugin.getVersion() + ", file=" + specificVersion.getResolvedValue().getSource().getPath());
            }
        }
    }

    private static ConfiguredValue updatePropertyOrReturnConfiguredValue(UpdateSteps updateSteps, String recommendedValue, ConfiguredValue configuredValue) {
        if(!recommendedValue.equals(configuredValue.getEffectiveValue())) {
            if(configuredValue.getResolvedValue().getSource().isProperty()) {
                updateSteps.addStep(PomPropertyUpdateStep.of(configuredValue.getResolvedValue(), recommendedValue));
            } else {
                return configuredValue;
            }
        }
        return null;
    }

    private static void addDependencyUpdateSteps(ConfiguredApplication app, RegistryProjectInfo registryProjectInfo, UpdateSteps updateSteps) {
        final Map<ArtifactKey, String> recommendedExtensions = getRecommendedExtensionVersions(registryProjectInfo);
        for (var configuredExt : app.getTopExtensionDependencies()) {
            final String recommendedVersion = recommendedExtensions.remove(configuredExt.getKey());
            if (recommendedVersion == null) {
                // TODO remove dependency
                System.out
                        .println("remove " + configuredExt.toCompactString() + " from " + configuredExt.getConfigurationFile());
            } else if (MANAGED_VERSION.equals(recommendedVersion)) {
                if (configuredExt.getVersion().getRawValue() != null) {
                    // TODO make sure the version is not configured
                    System.out.println(
                            "unset version " + configuredExt.toCompactString() + " in " + configuredExt.getConfigurationFile());
                }
            } else if (!recommendedVersion.equals(configuredExt.getVersion().getEffectiveValue())) {
                var configuredVersionValue = configuredExt.getVersion().getResolvedValue();
                if (configuredVersionValue.getSource().isProperty()) {
                    updateSteps.addStep(PomPropertyUpdateStep.of(configuredVersionValue, recommendedVersion));
                } else {
                    // TODO set specific version
                    System.out.println("set version " + configuredExt.toCompactString() + " -> " + recommendedVersion + " in "
                            + configuredExt.getConfigurationFile());
                }
            }
        }

        if (!recommendedExtensions.isEmpty()) {
            // TODO add new dependencies
            System.out.println("add dependencies:");
            for (var ext : recommendedExtensions.entrySet()) {
                System.out.println("- " + ext.getKey().toGacString() + ":" + ext.getValue());
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

    private List<UpdateStep> updateList = new ArrayList<>();

    public UpdateSteps() {
    }

    void addStep(UpdateStep step) {
        for (int i = 0; i < updateList.size(); ++i) {
            var existing = updateList.get(i);
            var superStep = getSuperStepOrNull(existing, step);
            if (superStep != null) {
                if (superStep != existing) {
                    updateList.set(i, step);
                }
                return;
            }
            if (!existing.isCompatible(step)) {
                throw new IllegalArgumentException(existing + " is not compatible with " + step);
            }
        }
        updateList.add(step);
    }

    private static UpdateStep getSuperStepOrNull(UpdateStep one, UpdateStep two) {
        var oneOutcomes = one.getOutcomes();
        var twoOutcomes = two.getOutcomes();
        if (oneOutcomes.size() >= twoOutcomes.size()) {
            return oneOutcomes.containsAll(twoOutcomes) ? one : null;
        }
        return twoOutcomes.containsAll(oneOutcomes) ? two : null;
    }

    public List<UpdateStep> asList() {
        return updateList;
    }
}
