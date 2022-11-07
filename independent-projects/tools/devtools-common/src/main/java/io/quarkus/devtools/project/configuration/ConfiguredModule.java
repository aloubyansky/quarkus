package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.ArtifactCoords;

public class ConfiguredModule {

    public static ConfiguredModule of(WorkspaceModuleId id, Path buildFile) {
        return new ConfiguredModule(id, buildFile);
    }

    private final WorkspaceModuleId id;
    private final Path buildFile;
    private final Map<ArtifactCoords, ConfiguredBom> platformBoms = new LinkedHashMap<>();
    private final Map<ArtifactCoords, ConfiguredArtifact> managedExtensions = new LinkedHashMap<>();
    private final List<ConfiguredArtifact> directExtensionDeps = new ArrayList<>();
    private final List<ConfiguredArtifact> topTransitiveExtensionDeps = new ArrayList<>(0);
    private ConfiguredArtifact quarkusPlugin;
    private ConfiguredArtifact managedQuarkusPlugin;
    private boolean quarkusApp;

    private ConfiguredModule(WorkspaceModuleId id, Path buildFile) {
        this.id = id;
        this.buildFile = buildFile;
    }

    public WorkspaceModuleId getId() {
        return id;
    }

    public Path getBuildFile() {
        return buildFile;
    }

    public void addPlatformBom(ConfiguredBom bom) {
        platformBoms.put(bom.getArtifact().getEffectiveCoords(), bom);
    }

    public Collection<ConfiguredBom> getPlatformBoms() {
        return platformBoms.values();
    }

    public void addManagedExtension(ConfiguredArtifact directExtensionDep) {
        managedExtensions.put(directExtensionDep.getEffectiveCoords(), directExtensionDep);
    }

    public Collection<ConfiguredArtifact> getManagedExtensions() {
        return managedExtensions.values();
    }

    public void addDirectExtensionDep(ConfiguredArtifact directExtensionDep) {
        directExtensionDeps.add(directExtensionDep);
    }

    public List<ConfiguredArtifact> getDirectExtensionDeps() {
        return directExtensionDeps;
    }

    public void addTopTransitiveExtensionDep(ConfiguredArtifact extensionDep) {
        topTransitiveExtensionDeps.add(extensionDep);
    }

    public List<ConfiguredArtifact> getTopTransitiveExtensionDeps() {
        return topTransitiveExtensionDeps;
    }

    public void setQuarkusPlugin(ConfiguredArtifact plugin) {
        this.quarkusPlugin = plugin;
    }

    public ConfiguredArtifact getQuarkusPlugin() {
        return quarkusPlugin;
    }

    public void setManagedQuarkusPlugin(ConfiguredArtifact plugin) {
        this.managedQuarkusPlugin = plugin;
    }

    public ConfiguredArtifact getManagedQuarkusPlugin() {
        return managedQuarkusPlugin;
    }

    public void setQuarkusApplication(boolean quarkusApp) {
        this.quarkusApp = quarkusApp;
    }

    public boolean isQuarkusApplication() {
        return quarkusApp;
    }
}
