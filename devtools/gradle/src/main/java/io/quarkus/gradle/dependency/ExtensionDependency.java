package io.quarkus.gradle.dependency;

import java.util.List;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.dsl.DependencyHandler;

public class ExtensionDependency {

    ModuleVersionIdentifier extensionId;
    List<Dependency> conditionalDependencies;
    List<Dependency> dependencyConditions;

    ExtensionDependency(ModuleVersionIdentifier extensionId, List<Dependency> conditionalDependencies,
            List<Dependency> dependencyConditions) {
        this.extensionId = extensionId;
        this.conditionalDependencies = conditionalDependencies;
        this.dependencyConditions = dependencyConditions;
    }

    public boolean needsResolution(Set<ResolvedArtifact> resolvedArtifacts) {
        for (Dependency dependency : conditionalDependencies) {
            if (!DependencyUtils.exists(resolvedArtifacts, dependency)) {
                return true;
            }
        }
        return false;
    }

    public void importConditionalDependency(DependencyHandler dependencies, ModuleVersionIdentifier capability) {
        Dependency dependency = findConditionalDependency(capability);

        if (dependency == null) {
            throw new GradleException("Trying to add " + capability.getName() + " variant which is not declared by "
                    + extensionId.getName() + " extension.");
        }

        dependencies.components(handler -> handler.withModule(toModuleName(),
                componentMetadataDetails -> componentMetadataDetails.allVariants(variantMetadata -> variantMetadata
                        .withDependencies(d -> d.add(DependencyUtils.asDependencyNotation(dependency))))));
    }

    public Dependency asDependency(DependencyHandler dependencies) {
        return dependencies.create(asDependencyNotation());
    }

    public String asDependencyNotation() {
        return String.join(":", this.extensionId.getGroup(), this.extensionId.getName(), this.extensionId.getVersion());
    }

    private Dependency findConditionalDependency(ModuleVersionIdentifier capability) {
        for (Dependency conditionalDependency : conditionalDependencies) {
            if (conditionalDependency.getGroup().equals(capability.getGroup())
                    && conditionalDependency.getName().equals(capability.getName())) {
                return conditionalDependency;
            }
        }
        return null;
    }

    private String toModuleName() {
        return String.join(":", this.extensionId.getGroup(), this.extensionId.getName());
    }

}
