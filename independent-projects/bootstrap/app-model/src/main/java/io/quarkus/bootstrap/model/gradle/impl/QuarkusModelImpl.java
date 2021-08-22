package io.quarkus.bootstrap.model.gradle.impl;

import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.bootstrap.model.gradle.Dependency;
import io.quarkus.bootstrap.model.gradle.QuarkusModel;
import io.quarkus.bootstrap.model.gradle.Workspace;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class QuarkusModelImpl implements QuarkusModel, Serializable {

    private final Workspace workspace;
    private final List<Dependency> appDependencies;
    private final List<Dependency> extensionDependencies;
    private final List<Dependency> enforcedPlatformDependencies;
    private final PlatformImports platformImports;
    private final Map<String, String> projectProperties;

    public QuarkusModelImpl(Workspace workspace,
            List<Dependency> appDependencies,
            List<Dependency> extensionDependencies,
            List<Dependency> enforcedPlatformDependencies,
            PlatformImports platformImports,
            Map<String, String> projectProperties) {
        this.workspace = workspace;
        this.appDependencies = appDependencies;
        this.extensionDependencies = extensionDependencies;
        this.enforcedPlatformDependencies = enforcedPlatformDependencies;
        this.platformImports = platformImports;
        this.projectProperties = projectProperties;
    }

    @Override
    public Workspace getWorkspace() {
        return workspace;
    }

    @Override
    public List<Dependency> getAppDependencies() {
        return appDependencies;
    }

    @Override
    public List<Dependency> getExtensionDependencies() {
        return extensionDependencies;
    }

    @Override
    public List<Dependency> getEnforcedPlatformDependencies() {
        return enforcedPlatformDependencies;
    }

    @Override
    public PlatformImports getPlatformImports() {
        return platformImports;
    }

    @Override
    public Map<String, String> getProjectProperties() {
        return projectProperties;
    }
}
