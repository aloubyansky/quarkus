package io.quarkus.gradle.dependency;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.plugins.JavaPlugin;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.util.ZipUtils;
import io.quarkus.gradle.QuarkusPlugin;

public class ConditionalDependenciesEnabler {

    public static final String QUARKUS_EXTENSION_CONFIGURATION_NAME = "quarkusExtension";

    private final Map<String, ExtensionDependency> featureVariants = new HashMap<>();
    private final Project project;
    private final Configuration extensionConfiguration;

    public ConditionalDependenciesEnabler(Project project) {
        this.project = project;
        this.extensionConfiguration = project.getConfigurations().create(QUARKUS_EXTENSION_CONFIGURATION_NAME);
    }

    public void addConditionalDependencies() {
        Configuration implementationCopy = DependencyUtils.duplicateConfiguration(project,
                project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME),
                project.getConfigurations().getByName(QuarkusPlugin.DEV_MODE_CONFIGURATION_NAME));
        Set<ResolvedArtifact> runtimeArtifacts = implementationCopy.getResolvedConfiguration().getResolvedArtifacts();
        List<ExtensionDependency> firstLevelExtensions = collectFirstLevelExtension(runtimeArtifacts);
        featureVariants.putAll(extractFeatureVariants(firstLevelExtensions));
        resolveConditionalDependencies(firstLevelExtensions, implementationCopy);
    }

    private List<ExtensionDependency> collectFirstLevelExtension(Set<ResolvedArtifact> runtimeArtifacts) {
        List<ExtensionDependency> firstLevelExtensions = new ArrayList<>();
        for (ResolvedArtifact artifact : runtimeArtifacts) {
            ExtensionDependency extension = getExtensionInfoOrNull(artifact);
            if (extension != null) {
                extensionConfiguration.getDependencies().add(extension.asDependency(project.getDependencies()));
                if (!extension.conditionalDependencies.isEmpty()) {
                    if (extension.needsResolution(runtimeArtifacts)) {
                        firstLevelExtensions.add(extension);
                    }
                }
            }
        }
        return firstLevelExtensions;
    }

    private Map<String, ExtensionDependency> extractFeatureVariants(List<ExtensionDependency> extensions) {
        Map<String, ExtensionDependency> possibleVariant = new HashMap<>();
        for (ExtensionDependency extension : extensions) {
            for (Dependency dependency : extension.conditionalDependencies) {
                possibleVariant.put(DependencyUtils.asFeatureName(dependency), extension);
            }
        }
        return possibleVariant;
    }

    private void resolveConditionalDependencies(List<ExtensionDependency> extensions, Configuration existingDependencies) {
        final Configuration conditionalDeps = createConditionalDependenciesConfiguration(existingDependencies,
                extensions);
        boolean hasChanged = false;
        Set<ResolvedArtifact> runtimeArtifacts = existingDependencies.getResolvedConfiguration().getResolvedArtifacts();
        Map<String, ExtensionDependency> validConditionalDependencies = new HashMap<>();

        for (ResolvedArtifact artifact : conditionalDeps.getResolvedConfiguration().getResolvedArtifacts()) {
            ExtensionDependency extensionDependency = getExtensionInfoOrNull(artifact);
            if (extensionDependency != null) {
                if (DependencyUtils.exist(runtimeArtifacts, extensionDependency.dependencyConditions)) {
                    enableConditionalDependency(extensionDependency.extensionId);
                    validConditionalDependencies.put(DependencyUtils.asFeatureName(extensionDependency.extensionId),
                            extensionDependency);
                    if (!extensionDependency.conditionalDependencies.isEmpty()) {
                        featureVariants.putAll(extractFeatureVariants(Collections.singletonList(extensionDependency)));
                    }
                }
            }
        }

        Configuration enhancedDependencies = DependencyUtils.duplicateConfiguration(project,
                project.getConfigurations().getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME));

        List<ExtensionDependency> newConditionalDependencies = new ArrayList<>();

        // The dependency may have been excluded, if no, no need to look for transitive dependencies
        Set<ResolvedArtifact> resolvedArtifacts = enhancedDependencies.getResolvedConfiguration()
                .getResolvedArtifacts();

        for (ExtensionDependency extension : validConditionalDependencies.values()) {
            if (DependencyUtils.exists(resolvedArtifacts, extension.asDependency(project.getDependencies()))) {
                hasChanged = true;
                extensionConfiguration.getDependencies().add(extension.asDependency(project.getDependencies()));
                if (!extension.conditionalDependencies.isEmpty()) {
                    newConditionalDependencies.add(extension);
                }
            }
        }
        if (hasChanged) {
            for (ExtensionDependency extension : extensions) {
                if (!validConditionalDependencies.containsKey(DependencyUtils.asFeatureName(extension.extensionId))) {
                    newConditionalDependencies.add(extension);
                }
            }
            if (!newConditionalDependencies.isEmpty()) {
                resolveConditionalDependencies(newConditionalDependencies, enhancedDependencies);
            }
        }
    }

    private Configuration createConditionalDependenciesConfiguration(Configuration existingDeps,
            List<ExtensionDependency> extensions) {
        Set<ResolvedArtifact> runtimeArtifacts = existingDeps.getResolvedConfiguration().getResolvedArtifacts();
        List<Dependency> toResolve = new ArrayList<>();
        for (Dependency dependency : collectConditionalDependencies(extensions)) {
            if (!DependencyUtils.exists(runtimeArtifacts, dependency)) {
                toResolve.add(dependency);
            }
        }
        return project.getConfigurations()
                .detachedConfiguration(toResolve.toArray(new Dependency[0]));
    }

    private Set<Dependency> collectConditionalDependencies(List<ExtensionDependency> extensionDependencies) {
        Set<Dependency> dependencies = new HashSet<>();
        for (ExtensionDependency extensionDependency : extensionDependencies) {
            dependencies.addAll(extensionDependency.conditionalDependencies);
        }
        return dependencies;
    }

    private void enableConditionalDependency(ModuleVersionIdentifier dependency) {
        ExtensionDependency extension = featureVariants.get(DependencyUtils.asFeatureName(dependency));
        if (extension == null) {
            return;
        }
        extension.importConditionalDependency(project.getDependencies(), dependency);
    }

    private ExtensionDependency getExtensionInfoOrNull(ResolvedArtifact artifact) {
        ModuleVersionIdentifier artifactId = artifact.getModuleVersion().getId();
        File artifactFile = artifact.getFile();
        if (artifactFile.isDirectory()) {
            Path descriptorPath = artifactFile.toPath().resolve(BootstrapConstants.DESCRIPTOR_PATH);
            if (Files.exists(descriptorPath)) {
                return loadExtensionInfo(descriptorPath, artifactId);
            }
        } else {
            try (FileSystem artifactFs = ZipUtils.newFileSystem(artifactFile.toPath())) {
                Path descriptorPath = artifactFs.getPath(BootstrapConstants.DESCRIPTOR_PATH);
                if (Files.exists(descriptorPath)) {
                    return loadExtensionInfo(descriptorPath, artifactId);
                }
            } catch (IOException e) {
                throw new GradleException("Failed to read " + artifactFile, e);
            }
        }
        return null;
    }

    private ExtensionDependency loadExtensionInfo(Path descriptorPath, ModuleVersionIdentifier exentionId) {
        final Properties extensionProperties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(descriptorPath)) {
            extensionProperties.load(reader);
        } catch (IOException e) {
            throw new GradleException("Failed to load " + descriptorPath, e);
        }
        List<Dependency> conditionalDependencies = new ArrayList<>();
        if (extensionProperties.containsKey(BootstrapConstants.CONDITIONAL_DEPENDENCIES)) {
            String conditionalDeps = extensionProperties.get(BootstrapConstants.CONDITIONAL_DEPENDENCIES).toString();
            for (String conditionalDep : conditionalDeps.split(",")) {
                conditionalDependencies.add(DependencyUtils.create(project.getDependencies(), conditionalDep));
            }
        }
        List<Dependency> constraints = new ArrayList<>();
        if (extensionProperties.containsKey(BootstrapConstants.DEPENDENCY_CONDITION)) {
            String constraintDeps = extensionProperties.getProperty(BootstrapConstants.DEPENDENCY_CONDITION);
            for (String constraint : constraintDeps.split(",")) {
                constraints.add(DependencyUtils.create(project.getDependencies(), constraint));
            }
        }
        return new ExtensionDependency(exentionId, conditionalDependencies, constraints);
    }
}
