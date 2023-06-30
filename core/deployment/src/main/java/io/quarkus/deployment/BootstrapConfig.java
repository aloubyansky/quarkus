package io.quarkus.deployment;

import java.nio.file.Path;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigRoot;

/**
 * This is used currently only to suppress warnings about unknown properties
 * when the user supplies something like: -Dquarkus.debug.reflection=true
 */
@ConfigRoot
public class BootstrapConfig {

    /**
     * If set to true, the workspace initialization will be based on the effective POMs
     * (i.e. properly interpolated, including support for profiles) instead of the raw ones.
     */
    @ConfigItem(defaultValue = "false")
    boolean effectiveModelBuilder;

    /**
     * If set to true, workspace discovery will be enabled for all launch modes.
     * Usually, workspace discovery is enabled by default only for dev and test modes.
     */
    @ConfigItem(defaultValue = "false")
    Boolean workspaceDiscovery;

    /**
     * Whether to throw an error, warn or silently ignore misaligned platform BOM imports
     */
    @ConfigItem(defaultValue = "error")
    public MisalignedPlatformImports misalignedPlatformImports;

    /**
     * Application configuration dumping options
     */
    @ConfigItem
    public DumpConfig configRecorder;

    public enum MisalignedPlatformImports {
        ERROR,
        WARN,
        IGNORE;
    }

    @ConfigGroup
    public static class DumpConfig {

        /**
         * Whether configuration dumping is enabled
         */
        @ConfigItem(defaultValue = "false")
        public boolean enabled;

        /**
         * Directory in which the configuration dump should be stored.
         * If not configured the {@code .quarkus} directory under the project directory will be used.
         */
        public Optional<Path> directory;

        /**
         * File to which the configuration should be dumped. If not configured, the {@link #filePrefix} and
         * {@link #fileSuffix} will be used to generate the final file name.
         * If the configured file path is absolute, the {@link #directory} option will be ignored. Otherwise,
         * the path will be considered relative to the {@link #directory}.
         */
        public Optional<Path> file;

        /**
         * File name prefix. This option will be ignored in case {@link #file} is configured.
         */
        @ConfigItem(defaultValue = "quarkus")
        public String filePrefix;

        /**
         * File name suffix. This option will be ignored in case {@link #file} is configured.
         */
        @ConfigItem(defaultValue = "-config-dump")
        public String fileSuffix;
    }
}
