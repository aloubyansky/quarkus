package io.quarkus.registry.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.json.JsonBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Asymmetric data manipulation:
 * Deserialization always uses the builder;
 * Serialization always uses the Impl.
 *
 * @see RegistryPlatformsConfig#builder() creates a builder
 * @see RegistryPlatformsConfig#mutable() creates a builder from an existing RegistriesConfig
 * @see JsonBuilder.JsonBuilderSerializer for building a builder before serializing it.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class RegistryPlatformsConfigImpl extends RegistryArtifactConfigImpl implements RegistryPlatformsConfig {

    protected final Boolean extensionCatalogsIncluded;
    protected final Map<String, Object> extra;

    private RegistryPlatformsConfigImpl(Builder builder) {
        super(builder.disabled, builder.artifact);
        this.extensionCatalogsIncluded = builder.extensionCatalogsIncluded;
        this.extra = JsonBuilder.toUnmodifiableMap(builder.extra);
    }

    @Override
    public Boolean getExtensionCatalogsIncluded() {
        return extensionCatalogsIncluded;
    }

    @Override
    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        if (!super.equals(o))
            return false;
        RegistryPlatformsConfigImpl that = (RegistryPlatformsConfigImpl) o;
        return Objects.equals(extensionCatalogsIncluded, that.extensionCatalogsIncluded)
                && Objects.equals(extra, that.extra);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), extensionCatalogsIncluded, extra);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() +
                "{disabled=" + disabled +
                ", artifact=" + artifact +
                ", extensionCatalogsIncluded=" + extensionCatalogsIncluded +
                ", extra=" + extra +
                '}';
    }

    public RegistryPlatformsConfig.Mutable mutable() {
        return new Builder(this);
    }

    /**
     * Builder.
     */
    public static class Builder extends RegistryArtifactConfigImpl.Builder implements RegistryPlatformsConfig.Mutable {
        protected Boolean extensionCatalogsIncluded;
        protected Map<String, Object> extra;

        public Builder() {
        }

        @JsonIgnore
        Builder(RegistryPlatformsConfig config) {
            super(config);
            this.extensionCatalogsIncluded = config.getExtensionCatalogsIncluded();
            this.extra = config.getExtra() == null
                    ? null
                    : new HashMap<>(config.getExtra());
        }

        @Override
        public RegistryPlatformsConfig.Mutable setDisabled(boolean disabled) {
            super.setDisabled(disabled);
            return this;
        }

        @Override
        public RegistryPlatformsConfig.Mutable setArtifact(ArtifactCoords artifact) {
            super.setArtifact(artifact);
            return this;
        }

        @Override
        public Boolean getExtensionCatalogsIncluded() {
            return extensionCatalogsIncluded;
        }

        @Override
        public RegistryPlatformsConfig.Mutable setExtensionCatalogsIncluded(Boolean extensionCatalogsIncluded) {
            this.extensionCatalogsIncluded = extensionCatalogsIncluded;
            return this;
        }

        @Override
        public Map<String, Object> getExtra() {
            return extra == null ? Collections.emptyMap() : extra;
        }

        @Override
        public RegistryPlatformsConfig.Mutable setExtra(Map<String, Object> newValues) {
            if (newValues != Collections.EMPTY_MAP) {
                this.extra = newValues;
            }
            return this;
        }

        @JsonAnySetter
        public Builder setExtra(String name, Object value) {
            if (extra == null) {
                extra = new HashMap<>();
            }
            extra.put(name, value);
            return this;
        }

        @Override
        public RegistryPlatformsConfigImpl build() {
            return new RegistryPlatformsConfigImpl(this);
        }
    }
}
