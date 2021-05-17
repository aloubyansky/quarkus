package io.quarkus.deployment;

import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigRoot;

/**
 * This is used currently only to suppress warnings about unknown properties
 * when the user supplies something like: -Dquarkus.debug.reflection=true
 */
@ConfigRoot
public class BootstrapConfig {

    public static final String MISALIGNED_PLATFORM_IMPORTS_ERROR = "error";
    public static final String MISALIGNED_PLATFORM_IMPORTS_WARN = "warn";
    public static final String MISALIGNED_PLATFORM_IMPORTS_IGNORE = "ignore";

    /**
     * If set to true, the workspace initialization will be based on the effective POMs
     * (i.e. properly interpolated, including support for profiles, etc) instead of the raw ones.
     */
    @ConfigItem(defaultValue = "false")
    boolean effectiveModelBuilder;

    /**
     * Whether to throw an error, warn or silently ignore misaligned platform BOM imports
     */
    @ConfigItem(defaultValue = MISALIGNED_PLATFORM_IMPORTS_ERROR)
    public String misalignedPlatformImports;
}
