package io.quarkus.registry.config;

import io.quarkus.maven.dependency.ArtifactCoords;
import java.util.Map;

/**
 * Configuration related to the resolution of catalogs of available platforms.
 */
public interface RegistryPlatformsConfig extends RegistryArtifactConfig {

    /**
     * Whether the client should send requests to resolve the platform extension catalogs (platform descriptors)
     * to the registry or resolve them from Maven Central directly instead.
     * Returning <code>null</code> from this method will be equivalent to returning <code>false</code>, in which case
     * the client will not send requests to resolve platform extension catalogs to the registry.
     *
     * @return true if the registry will be able to handle platform descriptor requests, otherwise - false
     */
    Boolean getExtensionCatalogsIncluded();

    /**
     * Custom registry client configuration.
     *
     * @return custom registry client configuration
     */
    Map<String, Object> getExtra();

    default Mutable mutable() {
        return new RegistryPlatformsConfigImpl.Builder(this);
    }

    interface Mutable extends RegistryPlatformsConfig, RegistryArtifactConfig.Mutable {

        @Override
        RegistryPlatformsConfig.Mutable setArtifact(ArtifactCoords artifact);

        @Override
        RegistryPlatformsConfig.Mutable setDisabled(boolean disabled);

        RegistryPlatformsConfig.Mutable setExtensionCatalogsIncluded(Boolean extensionCatalogsIncluded);

        default RegistryPlatformsConfig.Mutable setAny(String name, Object value) {
            setExtra(name, value);
            return this;
        }

        RegistryPlatformsConfig.Mutable setExtra(Map<String, Object> extra);

        RegistryPlatformsConfig.Mutable setExtra(String name, Object value);

        /** @return an immutable copy of this config */
        @Override
        RegistryPlatformsConfig build();
    }

    /**
     * @return a new mutable instance
     */
    static Mutable builder() {
        return new RegistryPlatformsConfigImpl.Builder();
    }
}
