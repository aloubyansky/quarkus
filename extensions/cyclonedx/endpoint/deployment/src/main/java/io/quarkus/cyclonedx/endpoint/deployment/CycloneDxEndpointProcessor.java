package io.quarkus.cyclonedx.endpoint.deployment;

import java.util.List;

import io.quarkus.cyclonedx.deployment.spi.EmbeddedSbomMetadataBuildItem;
import io.quarkus.cyclonedx.deployment.spi.EmbeddedSbomRequestBuildItem;
import io.quarkus.cyclonedx.endpoint.runtime.CycloneDxEndpointRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.vertx.http.deployment.spi.RouteBuildItem;

public class CycloneDxEndpointProcessor {

    @BuildStep
    void requestEmbeddedSbom(CycloneDxEndpointConfig config,
            BuildProducer<EmbeddedSbomRequestBuildItem> producer) {
        if (config.enabled()) {
            producer.produce(new EmbeddedSbomRequestBuildItem());
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    RouteBuildItem registerSbomEndpoint(CycloneDxEndpointConfig config,
            CycloneDxEndpointRecorder recorder,
            List<EmbeddedSbomMetadataBuildItem> embeddedSbomMetadata) {
        if (!config.enabled() || embeddedSbomMetadata.isEmpty()) {
            return null;
        }

        final EmbeddedSbomMetadataBuildItem metadata = embeddedSbomMetadata.get(0);
        String resourceName = metadata.getResourceName();
        String path = config.path();
        // derive content type from the base resource name (without .gz suffix)
        String baseName = resourceName.endsWith(".gz")
                ? resourceName.substring(0, resourceName.length() - 3)
                : resourceName;
        String contentType = baseName.endsWith(".xml")
                ? "application/vnd.cyclonedx+xml"
                : "application/vnd.cyclonedx+json";

        return RouteBuildItem.newAbsoluteRoute(path)
                .withRoutePathConfigKey("quarkus.cyclonedx.endpoint.path")
                .withRequestHandler(recorder.handler(resourceName, contentType, metadata.isCompressed()))
                .displayOnNotFoundPage("CycloneDX SBOM")
                .build();
    }
}
