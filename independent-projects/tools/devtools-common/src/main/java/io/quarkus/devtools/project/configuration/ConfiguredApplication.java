package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;

public class ConfiguredApplication {

    private final WorkspaceModuleId id;
    private final Path moduleConfigFile;
    private final List<ConfiguredBom> boms = new ArrayList<>();
    private final List<ConfiguredArtifact> topExtensions = new ArrayList<>();
    private ConfiguredArtifact quarkusPlugin;

    public ConfiguredApplication(WorkspaceModuleId id, Path moduleDir) {
        this.id = id;
        this.moduleConfigFile = moduleDir;
    }

    public WorkspaceModuleId getId() {
        return id;
    }

    public Path getModuleConfigFile() {
        return moduleConfigFile;
    }

    public List<ConfiguredBom> getPlatformBoms() {
        return boms;
    }

    public void addPlatformBom(ConfiguredBom platformBom) {
        boms.add(platformBom);
    }

    public List<ConfiguredArtifact> getTopExtensionDependencies() {
        return topExtensions;
    }

    public void addTopExtensionDependency(ConfiguredArtifact topExtensionDep) {
        topExtensions.add(topExtensionDep);
    }

    public void setQuarkusPlugin(ConfiguredArtifact quarkusPlugin) {
        this.quarkusPlugin = quarkusPlugin;
    }

    public ConfiguredArtifact getQuarkusPlugin() {
        return quarkusPlugin;
    }
}
