package io.quarkus.cyclonedx.deployment;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.quarkus.cyclonedx.generator.CycloneDxSbomGenerator;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AppModelProviderBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.sbom.ApplicationManifestsBuildItem;
import io.quarkus.deployment.sbom.SbomBuildItem;
import io.quarkus.sbom.ApplicationManifest;
import io.quarkus.sbom.ApplicationManifestConfig;

/**
 * Generates SBOMs for packaged applications if the corresponding config is enabled.
 * The API around this is still in development and will likely change in the near future.
 */
public class CdxSbomBuildStep {

    /**
     * Generates CycloneDX SBOMs from application manifests.
     *
     * @param applicationManifestsBuildItem application manifests
     * @param outputTargetBuildItem build output
     * @param appModelProviderBuildItem application model provider
     * @param cdxSbomConfig CycloneDX SBOM generation configuration
     * @param sbomProducer SBOM build item producer
     */
    @BuildStep
    public void generate(ApplicationManifestsBuildItem applicationManifestsBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            AppModelProviderBuildItem appModelProviderBuildItem,
            CycloneDxConfig cdxSbomConfig,
            BuildProducer<SbomBuildItem> sbomProducer) {
        if (cdxSbomConfig.skip() || applicationManifestsBuildItem.getManifests().isEmpty()) {
            // until there is a proper way to request the desired build items as build outcome
            return;
        }
        var depInfoProvider = appModelProviderBuildItem.getDependencyInfoProvider().get();
        for (var manifest : applicationManifestsBuildItem.getManifests()) {
            for (var sbom : CycloneDxSbomGenerator.newInstance()
                    .setManifest(manifest)
                    .setOutputDirectory(outputTargetBuildItem.getOutputDirectory())
                    .setEffectiveModelResolver(depInfoProvider == null ? null : depInfoProvider.getMavenModelResolver())
                    .setFormat(cdxSbomConfig.format())
                    .setSchemaVersion(cdxSbomConfig.schemaVersion().orElse(null))
                    .setIncludeLicenseText(cdxSbomConfig.includeLicenseText())
                    .generate()) {
                sbomProducer.produce(new SbomBuildItem(sbom));
            }
        }
    }

    @BuildStep
    public void embedDependencySbom(BuildProducer<GeneratedResourceBuildItem> generatedResourceBuildItem,
            CycloneDxConfig cdxConfig,
            CurateOutcomeBuildItem curateOutcomeBuildItem,
            AppModelProviderBuildItem appModelProviderBuildItem) {
        if (!cdxConfig.embeddedDependencySbom().enabled() || cdxConfig.skip()) {
            return;
        }

        final CycloneDxConfig.EmbeddedDependencySbomConfig dependencySbomConfig = cdxConfig.embeddedDependencySbom();
        final String resourceName = dependencySbomConfig.resourceName();
        if (resourceName == null || resourceName.isEmpty()) {
            throw new IllegalArgumentException("resourceName is not configured for the embedded dependency SBOM");
        }

        var depInfoProvider = appModelProviderBuildItem.getDependencyInfoProvider().get();
        List<String> result = CycloneDxSbomGenerator.newInstance()
                .setManifest(ApplicationManifest.fromConfig(ApplicationManifestConfig.builder()
                        .setApplicationModel(curateOutcomeBuildItem.getApplicationModel())
                        .build()))
                .setEffectiveModelResolver(depInfoProvider == null ? null : depInfoProvider.getMavenModelResolver())
                .setFormat(getFormat(resourceName))
                .setSchemaVersion(cdxConfig.schemaVersion().orElse(null))
                .setIncludeLicenseText(cdxConfig.includeLicenseText())
                .generateText();

        if (result.size() != 1) {
            // this should never happen
            throw new RuntimeException(
                    "Embedded dependency SBOM has more than 1 result for configured resource " + resourceName);
        }

        generatedResourceBuildItem
                .produce(new GeneratedResourceBuildItem(resourceName, result.get(0).getBytes(StandardCharsets.UTF_8)));
    }

    private static String getFormat(String resourceName) {
        int lastDot = resourceName.lastIndexOf('.');
        return lastDot == -1 ? "json" : resourceName.substring(lastDot + 1);
    }
}
