package io.quarkus.devtools.project.configuration.step;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.devtools.commands.handlers.RegistryProjectInfo;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.devtools.project.configuration.ConfiguredBom;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.catalog.ExtensionCatalog;

class BomMapper {

    private final List<BomUpdateDetails> bomUpdateDetails;
    private final Map<ArtifactKey, ArtifactCoords> recommendedBomsByGa;
    private Map<String, ArtifactCoords> recommendedBomsByArtifactId = null;

    BomMapper(ConfiguredApplication module, RegistryProjectInfo registryProjectInfo) {
        final List<ExtensionCatalog> extensionOrigins = registryProjectInfo.getExtensionOrigins();
        bomUpdateDetails = new ArrayList<>(extensionOrigins.size());
        recommendedBomsByGa = mapToGa(extensionOrigins, registryProjectInfo.getPrimaryPlatformBom());
        map(module.getPlatformBoms(), registryProjectInfo.getPrimaryPlatformBom().getBom());
    }

    private void map(Collection<ConfiguredBom> configuredBoms, ArtifactCoords recommendedPrimaryBom) {

        // we know which platform BOM the registry recommends as the primary (the dominating) one,
        // we need to figure out what it is in the current project
        var configuredPrimaryBom = getConfiguredPrimaryBom(configuredBoms);
        bomUpdateDetails.add(BomUpdateDetails.of(configuredPrimaryBom, recommendedPrimaryBom));

        // map the rest of the configured platform BOMs to the recommended ones, if they map at all
        for (var configuredBom : configuredBoms) {
            if (configuredPrimaryBom != configuredBom) {
                map(configuredBom);
            }
        }

        // add those recommended BOMs that couldn't be mapped
        if (!recommendedBomsByGa.isEmpty()) {
            for (var recommendedBom : recommendedBomsByGa.values()) {
                bomUpdateDetails.add(BomUpdateDetails.of(null, recommendedBom));
            }
        }
    }

    void addSteps(UpdateSteps updateSteps) {
        for (var bomUpdate : bomUpdateDetails) {
            bomUpdate.addSteps(updateSteps);
        }
    }

    private void map(ConfiguredBom configuredBom) {
        final ArtifactCoords recommendedCoords = getRecommendedBomOrNull(
                ArtifactKey.ga(configuredBom.getArtifact().getGroupId().getEffectiveValue(),
                        configuredBom.getArtifact().getArtifactId().getEffectiveValue()));
        bomUpdateDetails.add(BomUpdateDetails.of(configuredBom, recommendedCoords));
    }

    private ArtifactCoords getRecommendedBomOrNull(ArtifactKey configuredGa) {
        ArtifactCoords recommendedBom = recommendedBomsByGa.remove(configuredGa);
        if (recommendedBom == null) {
            recommendedBom = getRecommendedBomsByArtifactId().get(configuredGa.getArtifactId());
            if (recommendedBom != null) {
                recommendedBomsByGa.remove(ArtifactKey.ga(recommendedBom.getGroupId(), recommendedBom.getArtifactId()));
            }
        }
        return recommendedBom;
    }

    private Map<String, ArtifactCoords> getRecommendedBomsByArtifactId() {
        return recommendedBomsByArtifactId == null
                ? recommendedBomsByArtifactId = mapToArtifactId(recommendedBomsByGa.values())
                : recommendedBomsByArtifactId;
    }

    private static Map<ArtifactKey, ArtifactCoords> mapToGa(Collection<ExtensionCatalog> bomCatalogs,
            ExtensionCatalog primaryPlatformBom) {
        final Map<ArtifactKey, ArtifactCoords> result = new HashMap<>(bomCatalogs.size() - 1);
        for (var c : bomCatalogs) {
            if (c.isPlatform()) {
                final ArtifactCoords bom = c.getBom();
                if (!bom.equals(primaryPlatformBom.getBom())) {
                    result.put(ArtifactKey.ga(bom.getGroupId(), bom.getArtifactId()), bom);
                }
            }
        }
        return result;
    }

    private static Map<String, ArtifactCoords> mapToArtifactId(Collection<ArtifactCoords> bomCatalogs) {
        final Map<String, ArtifactCoords> result = new HashMap<>(bomCatalogs.size());
        for (var c : bomCatalogs) {
            result.put(c.getArtifactId(), c);
        }
        return result;
    }

    /**
     * Attempts to figure out what the primary platform BOM is in a project.
     * It does it using the same logic the registry client uses to recommend the primary BOM.
     *
     * @param configuredBoms configured platform BOM imports
     * @return primary platform BOM
     */
    private static ConfiguredBom getConfiguredPrimaryBom(Collection<ConfiguredBom> configuredBoms) {
        ConfiguredBom primaryConfiguredBom = null;
        for (ConfiguredBom bom : configuredBoms) {
            if (bom.getArtifact().getArtifactId().getEffectiveValue().equals("quarkus-bom")) {
                primaryConfiguredBom = bom;
                break;
            } else if (primaryConfiguredBom == null) {
                primaryConfiguredBom = bom;
            }
        }
        return primaryConfiguredBom;
    }

    private static class BomUpdateDetails {

        private static BomUpdateDetails of(ConfiguredBom configuredBom, ArtifactCoords registryRecommendedBom) {
            return new BomUpdateDetails(configuredBom, registryRecommendedBom);
        }

        final ConfiguredBom configuredBom;
        final ArtifactCoords registryRecommendedBom;

        private BomUpdateDetails(ConfiguredBom configuredBom, ArtifactCoords registryRecommendedBom) {
            this.configuredBom = configuredBom;
            this.registryRecommendedBom = registryRecommendedBom;
        }

        private void addSteps(UpdateSteps updateSteps) {
            if (configuredBom == null) {
                updateSteps.addStep(new BomImportAddStep(registryRecommendedBom, null /* TODO */));
            } else if (registryRecommendedBom == null) {
                if (configuredBom.getArtifact().getConfigurationFile() == null) {
                    throw new RuntimeException("The configuration file was not found in the workspace for " + configuredBom);
                }
                updateSteps.addStep(new BomImportRemoveStep(configuredBom.getArtifact().getRawCoords(),
                        configuredBom.getArtifact().getConfigurationFile()));
            } else {
                String newRawGroupId = null;
                String newRawArtifactId = null;
                String newRawVersion = null;
                if (!configuredBom.getArtifact().getGroupId().getEffectiveValue().equals(registryRecommendedBom.getGroupId())) {
                    if (configuredBom.getArtifact().getGroupId().isProperty()) {
                        updateSteps.addStep(PomPropertyUpdateStep.of(configuredBom.getArtifact().getGroupId(),
                                registryRecommendedBom.getGroupId()));
                    } else {
                        newRawGroupId = registryRecommendedBom.getGroupId();
                    }
                }
                if (!configuredBom.getArtifact().getArtifactId().getEffectiveValue()
                        .equals(registryRecommendedBom.getArtifactId())) {
                    if (configuredBom.getArtifact().getArtifactId().isProperty()) {
                        updateSteps.addStep(PomPropertyUpdateStep.of(configuredBom.getArtifact().getArtifactId(),
                                registryRecommendedBom.getArtifactId()));
                    } else {
                        newRawArtifactId = registryRecommendedBom.getArtifactId();
                    }
                }
                if (!configuredBom.getArtifact().getVersion().getEffectiveValue().equals(registryRecommendedBom.getVersion())) {
                    if (configuredBom.getArtifact().getVersion().isProperty()) {
                        updateSteps.addStep(PomPropertyUpdateStep.of(configuredBom.getArtifact().getVersion(),
                                registryRecommendedBom.getVersion()));
                    } else {
                        newRawVersion = registryRecommendedBom.getVersion();
                    }
                }
                if (newRawGroupId != null || newRawArtifactId != null || newRawVersion != null) {
                    updateSteps.addStep(new BomImportUpdateStep(configuredBom.getArtifact().getRawCoords(), ArtifactCoords.pom(
                            newRawGroupId == null ? configuredBom.getArtifact().getGroupId().getRawValue()
                                    : newRawGroupId,
                            newRawArtifactId == null ? configuredBom.getArtifact().getArtifactId().getRawValue()
                                    : newRawArtifactId,
                            newRawVersion == null ? configuredBom.getArtifact().getVersion().getRawValue()
                                    : newRawVersion),
                            configuredBom.getArtifact().getConfigurationFile()));
                }
            }
        }
    }
}
