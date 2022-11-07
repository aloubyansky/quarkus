package io.quarkus.devtools.project.state.info;

import java.util.List;

import io.quarkus.devtools.project.state.ConfiguredBom;
import io.quarkus.registry.catalog.ExtensionOrigin;

public class ExtensionOriginInfo {

    private ConfiguredBom bom;
    private ExtensionOrigin metadata;
    private List<ExtensionInfo> extensions;
}
