package io.quarkus.cyclonedx.endpoint.deployment;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * CycloneDX SBOM endpoint configuration
 */
@ConfigMapping(prefix = "quarkus.cyclonedx.endpoint")
@ConfigRoot
public interface CycloneDxEndpointConfig {

    /**
     * Whether the embedded SBOM should be exposed through a REST endpoint.
     *
     * @return true, if the embedded SBOM should be exposed through a REST endpoint, otherwise - false
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * REST endpoint path that will provide an SBOM
     *
     * @return REST endpoint path that will provide an SBOM
     */
    @WithDefault("/.well-known/sbom")
    String path();

    /**
     * Base resource name of the embedded SBOM to expose through the endpoint.
     * When not configured and a single embedded SBOM is available, it will be used automatically.
     * When not configured and multiple embedded SBOMs are available, the default
     * {@code META-INF/sbom/dependency.cdx.json} will be used.
     *
     * @return base resource name of the embedded SBOM to expose
     */
    Optional<String> embeddedSbom();
}
