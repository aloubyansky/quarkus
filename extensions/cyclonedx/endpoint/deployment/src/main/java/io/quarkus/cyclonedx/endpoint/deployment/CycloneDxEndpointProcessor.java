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

    private static final String DEFAULT_RESOURCE_NAME = "META-INF/sbom/dependency.cdx.json";

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    RouteBuildItem registerSbomEndpoint(CycloneDxEndpointConfig config,
            CycloneDxEndpointRecorder recorder,
            List<EmbeddedSbomMetadataBuildItem> embeddedSbomMetadata) {
        if (!config.enabled() || embeddedSbomMetadata.isEmpty()) {
            return null;
        }

        final EmbeddedSbomMetadataBuildItem metadata = resolveMetadata(config, embeddedSbomMetadata);
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

    private static EmbeddedSbomMetadataBuildItem resolveMetadata(CycloneDxEndpointConfig config,
            List<EmbeddedSbomMetadataBuildItem> metadata) {
        if (config.embeddedSbom().isPresent()) {
            String target = config.embeddedSbom().get();
            EmbeddedSbomMetadataBuildItem match = findByResourceName(metadata, target);
            if (match == null) {
                throw new RuntimeException(
                        "No embedded SBOM matching '" + target + "' found. Available: " + resourceNames(metadata));
            }
            return match;
        }
        if (metadata.size() == 1) {
            return metadata.get(0);
        }
        EmbeddedSbomMetadataBuildItem match = findByResourceName(metadata, DEFAULT_RESOURCE_NAME);
        if (match == null) {
            throw new RuntimeException(
                    "Multiple embedded SBOMs available but none matching the default '" + DEFAULT_RESOURCE_NAME
                            + "'. Configure quarkus.cyclonedx.endpoint.embedded-sbom to select one. Available: "
                            + resourceNames(metadata));
        }
        return match;
    }

    private static EmbeddedSbomMetadataBuildItem findByResourceName(List<EmbeddedSbomMetadataBuildItem> metadata,
            String target) {
        for (var m : metadata) {
            String name = m.getResourceName();
            if (name.startsWith(target)) {
                if (name.length() == target.length()
                        || name.length() == target.length() + 3 && name.endsWith(".gz")) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String resourceNames(List<EmbeddedSbomMetadataBuildItem> metadata) {
        var sb = new StringBuilder();
        for (int i = 0; i < metadata.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(metadata.get(i).getResourceName());
        }
        return sb.toString();
    }
}
