package io.quarkus.devtools.project.state.info;

import java.util.List;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

public class ModuleInfo {

    private WorkspaceModuleId id;
    private List<ExtensionOriginInfo> extensionOrigins;
    private PluginInfo plugin;
}
