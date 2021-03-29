package io.quarkus.gradle.dependency;

import java.util.List;
import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Category;
import org.gradle.api.initialization.IncludedBuild;

import io.quarkus.bootstrap.model.AppArtifactCoords;

public class DependencyUtils {

    private static final String COPY_CONFIGURATION_NAME = "configurationCopy";

    public static Configuration duplicateConfiguration(Project project, Configuration... toDuplicates) {
        Configuration configurationCopy = project.getConfigurations().findByName(COPY_CONFIGURATION_NAME);
        if (configurationCopy != null) {
            project.getConfigurations().remove(configurationCopy);
        }

        configurationCopy = project.getConfigurations().create(COPY_CONFIGURATION_NAME);

        for (Configuration toDuplicate : toDuplicates) {
            for (Dependency dependency : toDuplicate.getDependencies()) {
                if (includedBuild(project, dependency.getName()) != null) {
                    continue;
                }
                if (!(dependency instanceof ProjectDependency)) {
                    configurationCopy.getDependencies().add(dependency);
                }
            }
        }
        return configurationCopy;
    }

    public static Dependency create(DependencyHandler dependencies, String conditionalDependency) {
        AppArtifactCoords dependencyCoords = AppArtifactCoords.fromString(conditionalDependency);
        return dependencies.create(String.join(":", dependencyCoords.getGroupId(), dependencyCoords.getArtifactId(),
                dependencyCoords.getVersion()));
    }

    public static boolean exist(Set<ResolvedArtifact> runtimeArtifacts, List<Dependency> dependencies) {
        for (Dependency dependency : dependencies) {
            if (!exists(runtimeArtifacts, dependency)) {
                return false;
            }
        }
        return true;
    }

    public static boolean exists(Set<ResolvedArtifact> runtimeArtifacts, Dependency dependency) {
        for (ResolvedArtifact runtimeArtifact : runtimeArtifacts) {
            ModuleVersionIdentifier artifactId = runtimeArtifact.getModuleVersion().getId();
            if (artifactId.getGroup().equals(dependency.getGroup()) && artifactId.getName().equals(dependency.getName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEnforcedPlatform(ModuleDependency module) {
        final Category category = module.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE);
        return category != null && (Category.ENFORCED_PLATFORM.equals(category.getName())
                || Category.REGULAR_PLATFORM.equals(category.getName()));
    }

    public static IncludedBuild includedBuild(final Project project, final String projectName) {
        try {
            return project.getGradle().includedBuild(projectName);
        } catch (UnknownDomainObjectException ignore) {
            return null;
        }
    }

    public static String asDependencyNotation(Dependency dependency) {
        return String.join(":", dependency.getGroup(), dependency.getName(), dependency.getVersion());
    }

    public static String asFeatureName(ModuleVersionIdentifier version) {
        return version.getGroup() + ":" + version.getName();
    }

    public static String asFeatureName(Dependency version) {
        return version.getGroup() + ":" + version.getName();
    }
}
