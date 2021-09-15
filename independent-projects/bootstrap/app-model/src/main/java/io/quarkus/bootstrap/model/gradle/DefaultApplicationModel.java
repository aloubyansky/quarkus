package io.quarkus.bootstrap.model.gradle;

import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.maven.dependency.Artifact;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.Dependency;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DefaultApplicationModel implements ApplicationModel, Serializable {

    private final Artifact appArtifact;
    private final List<Dependency> dependencies;
    private final PlatformImports platformImports;
    private final List<ExtensionCapabilities> capabilityContracts;
    private final Set<ArtifactKey> parentFirstArtifacts;
    private final Set<ArtifactKey> runnerParentFirstArtifacts;
    private final Set<ArtifactKey> lesserPriorityArtifacts;
    private final Set<ArtifactKey> localProjectArtifacts = new HashSet<>();

    public DefaultApplicationModel(ApplicationModelBuilder builder) {
        this.appArtifact = builder.appArtifact;
        this.dependencies = builder.filter(builder.dependencies);
        this.platformImports = builder.platformImports;
        this.capabilityContracts = builder.extensionCapabilities;
        this.parentFirstArtifacts = builder.parentFirstArtifacts;
        this.runnerParentFirstArtifacts = builder.runnerParentFirstArtifacts;
        this.lesserPriorityArtifacts = builder.lesserPriorityArtifacts;
    }

    @Override
    public Artifact getAppArtifact() {
        return appArtifact;
    }

    @Override
    public List<Dependency> getDependencies() {
        return dependencies;
    }

    @Override
    public PlatformImports getPlatformImports() {
        return platformImports;
    }

    @Override
    public Collection<ExtensionCapabilities> getExtensionCapabilities() {
        return capabilityContracts;
    }

    @Override
    public Set<ArtifactKey> getParentFirst() {
        return parentFirstArtifacts;
    }

    @Override
    public Set<ArtifactKey> getRunnerParentFirst() {
        return runnerParentFirstArtifacts;
    }

    @Override
    public Set<ArtifactKey> getLowerPriorityArtifacts() {
        return lesserPriorityArtifacts;
    }

    @Override
    public Set<ArtifactKey> getLocalProjectDependencies() {
        return localProjectArtifacts;
    }
}
