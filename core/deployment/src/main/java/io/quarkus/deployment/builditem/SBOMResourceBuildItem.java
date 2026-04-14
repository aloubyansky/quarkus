package io.quarkus.deployment.builditem;

import java.util.Optional;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * A resource name build item used for embedding SBOMs in GraalVM native images
 */
public final class SBOMResourceBuildItem extends SimpleBuildItem {

    private final Optional<String> sbomResourceName;

    public SBOMResourceBuildItem(Optional<String> sbomResourceName) {
        this.sbomResourceName = sbomResourceName;
    }

    public Optional<String> resourceName() {
        return sbomResourceName;
    }

}
