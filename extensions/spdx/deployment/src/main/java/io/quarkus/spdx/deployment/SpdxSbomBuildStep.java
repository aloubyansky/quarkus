package io.quarkus.spdx.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AppModelProviderBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.sbom.ApplicationManifestsBuildItem;
import io.quarkus.deployment.sbom.SbomBuildItem;
import io.quarkus.spdx.generator.SpdxSbomGenerator;

/**
 * Generates SPDX SBOMs for packaged applications if the corresponding config is enabled.
 */
public class SpdxSbomBuildStep {

    /**
     * Generates SPDX SBOMs from application manifests.
     *
     * @param applicationManifestsBuildItem application manifests
     * @param outputTargetBuildItem build output
     * @param appModelProviderBuildItem application model provider
     * @param spdxConfig SPDX SBOM generation configuration
     * @param sbomProducer SBOM build item producer
     */
    @BuildStep
    public void generate(ApplicationManifestsBuildItem applicationManifestsBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            AppModelProviderBuildItem appModelProviderBuildItem,
            SpdxConfig spdxConfig,
            BuildProducer<SbomBuildItem> sbomProducer) {
        if (spdxConfig.skip() || applicationManifestsBuildItem.getManifests().isEmpty()) {
            return;
        }
        var depInfoProvider = appModelProviderBuildItem.getDependencyInfoProvider().get();
        for (var manifest : applicationManifestsBuildItem.getManifests()) {
            for (var sbom : SpdxSbomGenerator.newInstance()
                    .setManifest(manifest)
                    .setOutputDirectory(outputTargetBuildItem.getOutputDirectory())
                    .setEffectiveModelResolver(depInfoProvider == null ? null : depInfoProvider.getMavenModelResolver())
                    .setFormat(spdxConfig.format())
                    .setSchemaVersion(spdxConfig.schemaVersion().orElse(null))
                    .setIncludeLicenseText(spdxConfig.includeLicenseText())
                    .generate()) {
                sbomProducer.produce(new SbomBuildItem(sbom));
            }
        }
    }
}
