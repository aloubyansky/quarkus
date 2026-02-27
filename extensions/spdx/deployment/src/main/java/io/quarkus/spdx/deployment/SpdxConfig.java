package io.quarkus.spdx.deployment;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * SPDX SBOM generator configuration
 */
@ConfigMapping(prefix = "quarkus.spdx")
@ConfigRoot
public interface SpdxConfig {
    /**
     * Whether to skip SBOM generation
     */
    @WithDefault("false")
    boolean skip();

    /**
     * SBOM file format. Supported formats are {@code json} and {@code tag-value}.
     * The default format is JSON.
     * If both are desired then {@code all} could be used as the value of this option.
     *
     * @return SBOM file format
     */
    @WithDefault("json")
    String format();

    /**
     * SPDX specification version. The default value will be 2.3.
     *
     * @return SPDX specification version
     */
    Optional<String> schemaVersion();

    /**
     * Whether to include the license text into generated SBOMs.
     *
     * @return whether to include the license text into generated SBOMs
     */
    @WithDefault("false")
    boolean includeLicenseText();
}
