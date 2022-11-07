package io.quarkus.devtools.project.state.info;

import io.quarkus.devtools.project.state.ConfiguredArtifact;
import io.quarkus.registry.catalog.Extension;

public class ExtensionInfo {

    private ConfiguredArtifact artifact;
    private Extension metadata;
    private boolean directDependency;
}
