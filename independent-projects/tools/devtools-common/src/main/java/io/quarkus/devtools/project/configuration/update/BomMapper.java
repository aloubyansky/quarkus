package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.devtools.commands.handlers.RegistryProjectInfo;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.configuration.ConfiguredApplication;
import io.quarkus.devtools.project.configuration.ConfiguredArtifact;
import io.quarkus.devtools.project.configuration.ConfiguredBom;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.catalog.ExtensionCatalog;

class BomMapper {

    private final List<BomUpdateDetails> bomUpdateDetails;
    private final Map<ArtifactKey, ArtifactCoords> recommendedBomsByGa;
    private final RegistryProjectInfo registryProjectInfo;
    private final MessageWriter log;
    private Map<String, String> propertyNamesByValue;
    private Map<String, ArtifactCoords> recommendedBomsByArtifactId = null;

    BomMapper(ConfiguredApplication module, RegistryProjectInfo registryProjectInfo, MessageWriter log) {
        final List<ExtensionCatalog> extensionOrigins = registryProjectInfo.getExtensionOrigins();
        bomUpdateDetails = new ArrayList<>(extensionOrigins.size());
        recommendedBomsByGa = mapToGa(extensionOrigins, registryProjectInfo.getPrimaryPlatformBom());
        this.registryProjectInfo = registryProjectInfo;
        this.log = log;
        map(module.getPlatformBoms(), registryProjectInfo.getPrimaryPlatformBom().getBom(), module.getModuleConfigFile());
    }

    private void map(Collection<ConfiguredBom> configuredBoms, ArtifactCoords recommendedPrimaryBom, Path moduleConfigFile) {

        // we know which platform BOM the registry recommends as the primary (the dominating) one,
        // we need to figure out what it is in the current project
        var configuredPrimaryBom = getConfiguredPrimaryBom(configuredBoms);
        bomUpdateDetails.add(detailsOf(configuredPrimaryBom, recommendedPrimaryBom));

        // map the rest of the configured platform BOMs to the recommended ones, if they map at all
        for (var configuredBom : configuredBoms) {
            if (configuredPrimaryBom != configuredBom) {
                map(configuredBom);
            }
        }

        // add those recommended BOMs that couldn't be mapped
        if (!recommendedBomsByGa.isEmpty()) {
            for (var recommendedBom : recommendedBomsByGa.values()) {
                bomUpdateDetails.add(detailsOf(moduleConfigFile, recommendedBom));
            }
        }
    }

    private Map<String, String> getPropertyNamesByValue() {
        if (propertyNamesByValue == null) {
            final Map<String, String> tmp = new HashMap<>(bomUpdateDetails.size());
            for (var details : bomUpdateDetails) {
                collectProperties(details, tmp);
            }
            propertyNamesByValue = tmp;
        }
        return propertyNamesByValue;
    }

    private void collectProperties(BomUpdateDetails details, Map<String, String> propertyNamesByValue) {
        final ConfiguredBom configuredBom = details.configuredBom;
        if (configuredBom == null) {
            return;
        }
        final ConfiguredArtifact artifact = configuredBom.getArtifact();
        if (artifact.getGroupId().isProperty()) {
            propertyNamesByValue.put(details.registryRecommendedBom.getGroupId(), artifact.getGroupId().getRawValue());
        }
        if (artifact.getVersion().isProperty()) {
            propertyNamesByValue.put(details.registryRecommendedBom.getVersion(), artifact.getVersion().getRawValue());
        }
    }

    void add(UpdateInstructions updateSteps) {
        final Set<ArtifactKey> updatedBoms = new HashSet<>();
        for (var bomUpdate : bomUpdateDetails) {
            bomUpdate.addSteps(updateSteps,
                    bomUpdate.configuredBom != null
                            && !updatedBoms.add(bomUpdate.configuredBom.getArtifact().getEffectiveCoords().getKey()));
        }
    }

    private void map(ConfiguredBom configuredBom) {
        final ArtifactCoords recommendedCoords = getRecommendedBomOrNull(
                ArtifactKey.ga(configuredBom.getArtifact().getGroupId().getEffectiveValue(),
                        configuredBom.getArtifact().getArtifactId().getEffectiveValue()));
        bomUpdateDetails.add(detailsOf(configuredBom, recommendedCoords));
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

    private BomUpdateDetails detailsOf(ConfiguredBom configuredBom, ArtifactCoords registryRecommendedBom) {
        return new BomUpdateDetails(configuredBom, registryRecommendedBom);
    }

    private BomUpdateDetails detailsOf(Path moduleConfigFile, ArtifactCoords registryRecommendedBom) {
        return new BomUpdateDetails(moduleConfigFile, registryRecommendedBom);
    }

    private class BomUpdateDetails {

        final ConfiguredBom configuredBom;
        final Path configurationFile;
        final ArtifactCoords registryRecommendedBom;

        private BomUpdateDetails(ConfiguredBom configuredBom, ArtifactCoords registryRecommendedBom) {
            this.configuredBom = configuredBom;
            this.configurationFile = configuredBom.getArtifact().getConfigurationFile();
            this.registryRecommendedBom = registryRecommendedBom;
        }

        private BomUpdateDetails(Path moduleConfigFile, ArtifactCoords registryRecommendedBom) {
            this.configuredBom = null;
            this.configurationFile = moduleConfigFile;
            this.registryRecommendedBom = registryRecommendedBom;
        }

        private void addSteps(UpdateInstructions updateSteps, boolean previouslyUpdatedElsewhere) {
            if (configuredBom == null) {
                updateSteps.add(BomInstruction.add(configurationFile, getRecommendedBomConfig()));
            } else if (registryRecommendedBom == null) {
                if (configuredBom.getArtifact().getConfigurationFile() == null) {
                    if (previouslyUpdatedElsewhere) {
                        log.warn("The configuration file was not found in the workspace for " + configuredBom
                                + " but " + configuredBom.getArtifact().getEffectiveCoords().toCompactCoords()
                                + " has already been updated elsewhere");
                        return;
                    } else {
                        throw new RuntimeException(
                                "The configuration file was not found in the workspace for " + configuredBom);
                    }
                }
                if (registryProjectInfo.canUpdateBom(configuredBom.getArtifact().getKey())) {
                    updateSteps.add(BomInstruction.remove(configuredBom.getArtifact().getConfigurationFile(),
                            configuredBom.getArtifact().getRawCoords()));
                }
            } else if (registryProjectInfo.canUpdateBom(configuredBom.getArtifact().getKey())) {
                String newRawGroupId = null;
                String newRawArtifactId = null;
                String newRawVersion = null;
                if (!configuredBom.getArtifact().getGroupId().getEffectiveValue().equals(registryRecommendedBom.getGroupId())) {
                    if (configuredBom.getArtifact().getGroupId().isProperty()) {
                        updateSteps.add(PomPropertyInstruction.update(configuredBom.getArtifact().getGroupId(),
                                registryRecommendedBom.getGroupId()));
                    } else {
                        newRawGroupId = registryRecommendedBom.getGroupId();
                    }
                }
                if (!configuredBom.getArtifact().getArtifactId().getEffectiveValue()
                        .equals(registryRecommendedBom.getArtifactId())) {
                    if (configuredBom.getArtifact().getArtifactId().isProperty()) {
                        updateSteps.add(PomPropertyInstruction.update(configuredBom.getArtifact().getArtifactId(),
                                registryRecommendedBom.getArtifactId()));
                    } else {
                        newRawArtifactId = registryRecommendedBom.getArtifactId();
                    }
                }
                if (!configuredBom.getArtifact().getVersion().getEffectiveValue().equals(registryRecommendedBom.getVersion())) {
                    if (configuredBom.getArtifact().getVersion().isProperty()) {
                        updateSteps.add(PomPropertyInstruction.update(configuredBom.getArtifact().getVersion(),
                                registryRecommendedBom.getVersion()));
                    } else {
                        newRawVersion = registryRecommendedBom.getVersion();
                    }
                }
                if (newRawGroupId != null || newRawArtifactId != null || newRawVersion != null) {
                    updateSteps.add(BomInstruction.update(
                            configuredBom.getArtifact().getConfigurationFile(),
                            configuredBom.getArtifact().getRawCoords(),
                            ArtifactCoords.pom(
                                    newRawGroupId == null ? configuredBom.getArtifact().getGroupId().getRawValue()
                                            : newRawGroupId,
                                    newRawArtifactId == null ? configuredBom.getArtifact().getArtifactId().getRawValue()
                                            : newRawArtifactId,
                                    newRawVersion == null ? configuredBom.getArtifact().getVersion().getRawValue()
                                            : newRawVersion)));
                }
            }
        }

        private ArtifactCoords getRecommendedBomConfig() {
            var propNamesByValues = getPropertyNamesByValue();
            var groupIdProp = propNamesByValues.get(registryRecommendedBom.getGroupId());
            var versionProp = propNamesByValues.get(registryRecommendedBom.getVersion());
            var recommendedBom = registryRecommendedBom;
            if (groupIdProp != null || versionProp != null) {
                recommendedBom = ArtifactCoords.pom(
                        groupIdProp == null ? recommendedBom.getGroupId() : groupIdProp,
                        recommendedBom.getArtifactId(),
                        versionProp == null ? recommendedBom.getVersion() : versionProp);
            }
            return recommendedBom;
        }
    }
}
