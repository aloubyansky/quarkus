package io.quarkus.gradle.dependency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.provider.ListProperty;

import io.quarkus.gradle.tooling.dependency.DependencyUtils;
import io.quarkus.gradle.tooling.dependency.ExtensionDependency;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.runtime.LaunchMode;

public class ConditionalDependencyResolver {

    public static String getConfigurationName(LaunchMode mode) {
        return "conditional" + ApplicationDeploymentClasspathBuilder.getLaunchModeAlias(mode)
                + "RuntimeConfiguration";
    }

    public static Attribute<String> getQuarkusConditionalDependencyAttribute() {
        return Attribute.of("quarkusConditionalDependency", String.class);
    }

    public static void resolve(Project project, LaunchMode mode, String deploymentConfigurationName) {
        new ConditionalDependencyResolver(project, mode).resolve(
                project.getConfigurations().getByName(ApplicationDeploymentClasspathBuilder.getBaseRuntimeConfigName(mode)),
                mode, deploymentConfigurationName);
    }

    private final Attribute<String> quarkusDepAttr = getQuarkusConditionalDependencyAttribute();
    private final Project project;
    private final Map<ArtifactKey, ProcessedDependency> processedDeps = new HashMap<>();
    private final Map<ArtifactKey, ConditionalDependency> allConditionalDeps = new HashMap<>();
    private final List<ConditionalDependencyVariant> dependencyVariantQueue = new ArrayList<>();
    private final boolean devMode;

    private ConditionalDependencyResolver(Project project, LaunchMode mode) {
        this.project = project;
        this.devMode = mode == LaunchMode.DEVELOPMENT;
    }

    private void resolve(Configuration baseConfig, LaunchMode mode, String deploymentConfigurationName) {

        final String configName = getConfigurationName(mode);
        project.getConfigurations().register(
                configName,
                config -> {
                    config.extendsFrom(baseConfig);
                    config.attributes(attrs -> {
                        attrs.attribute(quarkusDepAttr, deploymentConfigurationName);
                        //attrs.attribute(Attribute.of("org.gradle.libraryelements", String.class), LibraryElements.JAR);
                        //attrs.attribute(Attribute.of("org.gradle.dependency.bundling", String.class), Bundling.EXTERNAL);
                        //attrs.attribute(Attribute.of("org.gradle.usage", String.class), Usage.JAVA_RUNTIME);
                    });

                    System.out.println("resolveConditionalDeps " + project.getPath() + " for " + config.getName());
                    ListProperty<Dependency> dependencyListProperty = project.getObjects().listProperty(Dependency.class);
                    final AtomicInteger invocations = new AtomicInteger();
                    config.getDependencies().addAllLater(dependencyListProperty.value(project.provider(() -> {
                        if (invocations.getAndIncrement() == 0) {
                            activateConditionalDeps(baseConfig, deploymentConfigurationName);
                        }
                        return Set.of();
                    })));
                });
    }

    private void activateConditionalDeps(Configuration baseConfig, String deploymentConfigurationName) {
        processConfiguration(baseConfig.copyRecursive());
        while (!dependencyVariantQueue.isEmpty()) {
            boolean satisfiedConditions = false;
            var i = dependencyVariantQueue.iterator();
            while (i.hasNext()) {
                var conditionalVariant = i.next();
                if (conditionalVariant.conditionalDep.isConditionSatisfied()) {
                    satisfiedConditions = true;
                    // TODO add
                    System.out.println(
                            "Conditional variant "
                                    + conditionalVariant.parent.getExtensionId() + " -> "
                                    + conditionalVariant.conditionalDep.artifact + ", satisfied="
                                    + conditionalVariant.conditionalDep.isConditionSatisfied());
                    i.remove();

                    project.getDependencies().getComponents().all(compDetails -> {
                        if (compDetails.getId().getGroup().equals(conditionalVariant.parent.getGroup())
                                && compDetails.getId().getName().equals(conditionalVariant.parent.getName())
                                && compDetails.getId().getVersion()
                                        .equals(conditionalVariant.parent.getVersion())) {
                            compDetails.addVariant(
                                    "quarkusConditionalDependency" + deploymentConfigurationName,
                                    "compile",
                                    variant -> {
                                        variant.attributes(attrs -> {
                                            attrs.attribute(quarkusDepAttr, deploymentConfigurationName);
                                            System.out
                                                    .println("Adding variant " + deploymentConfigurationName
                                                            + " of " + compDetails.getId());
                                        });
                                        variant.withDependencies(directDeps -> {
                                            boolean alreadyAdded = false;
                                            for (var directDep : directDeps) {
                                                if (directDep.getName()
                                                        .equals(conditionalVariant.conditionalDep.key
                                                                .getArtifactId())
                                                        &&
                                                        directDep.getGroup().equals(
                                                                conditionalVariant.conditionalDep.key
                                                                        .getGroupId())) {
                                                    alreadyAdded = true;
                                                    break;
                                                }
                                            }
                                            if (!alreadyAdded) {
                                                var a = conditionalVariant.conditionalDep.artifact;
                                                directDeps.add(DependencyUtils.asDependencyNotation(project
                                                        .getDependencyFactory()
                                                        .create(a.getModuleVersion().getId().getGroup(),
                                                                a.getName(),
                                                                a.getModuleVersion().getId().getVersion(),
                                                                a.getClassifier(), a.getExtension())));
                                            }
                                        });
                                        /* @formatter:off
                                        variant.withFiles(files -> {
                                            //files.removeAllFiles();
                                            files.addFile(compDetails.getId().getName() + "-" + compDetails.getId().getVersion()
                                                    + ".jar");
                                        });
                                         @formatter:on */
                                    });
                        }
                    });
                }
            }
            if (!satisfiedConditions) {
                break;
            }
            final int queueSize = dependencyVariantQueue.size();
            processConfiguration(baseConfig.copyRecursive());
            if (queueSize == dependencyVariantQueue.size()) {
                break;
            }
        }
    }

    private void processConfiguration(Configuration config) {
        System.out.println("ConditionalDependencyResolver.processConfiguration " + config.getName());
        for (var dep : config.getResolvedConfiguration().getFirstLevelModuleDependencies()) {
            processDependency(dep);
        }
    }

    private void processDependency(ResolvedDependency dep) {
        boolean processChildren = false;
        var artifacts = dep.getModuleArtifacts();
        if (artifacts.isEmpty()) {
            processChildren = true;
        } else {
            for (var a : dep.getModuleArtifacts()) {
                var depKey = DependencyUtils.getKey(a);
                if (processedDeps.containsKey(depKey)) {
                    continue;
                }
                processChildren = true;
                var processedDep = new ProcessedDependency(depKey, DependencyUtils.getExtensionInfoOrNull(project, a));
                processedDeps.put(depKey, processedDep);
                processedDep.queueConditionalDeps();
            }
        }
        if (processChildren) {
            for (var c : dep.getChildren()) {
                processDependency(c);
            }
        }
    }

    private void queueConditionalDependency(ProcessedDependency parent, Dependency dep) {
        dependencyVariantQueue.add(new ConditionalDependencyVariant(parent.extension, getOrCreateConditionalDep(dep)));
    }

    private ConditionalDependency getOrCreateConditionalDep(Dependency dep) {
        return allConditionalDeps.computeIfAbsent(
                ArtifactKey.of(dep.getGroup(), dep.getName(), dep.getVersion(), ArtifactCoords.TYPE_JAR),
                key -> newConditionalDep(dep));
    }

    private ConditionalDependency newConditionalDep(Dependency dep) {
        for (var a : project.getConfigurations().detachedConfiguration(dep).setTransitive(false)
                .getResolvedConfiguration().getResolvedArtifacts()) {
            return new ConditionalDependency(DependencyUtils.getKey(a), a,
                    DependencyUtils.getExtensionInfoOrNull(project, a));
        }
        throw new RuntimeException(dep + " did not resolve to any artifacts");
    }

    private class ProcessedDependency {
        private final ArtifactKey key;
        private final ExtensionDependency<?> extension;

        private ProcessedDependency(ArtifactKey key, ExtensionDependency<?> extension) {
            this.key = key;
            this.extension = extension;
        }

        private boolean hasConditionalDeps() {
            return extension != null &&
                    (!extension.getConditionalDependencies().isEmpty() ||
                            devMode && !extension.getConditionalDevDependencies().isEmpty());
        }

        private void queueConditionalDeps() {
            if (extension == null) {
                return;
            }
            for (var dep : extension.getConditionalDependencies()) {
                queueConditionalDependency(this, dep);
            }
            if (devMode) {
                for (var dep : extension.getConditionalDevDependencies()) {
                    queueConditionalDependency(this, dep);
                }
            }
        }
    }

    private class ConditionalDependency {
        private final ArtifactKey key;
        private final ResolvedArtifact artifact;
        private final ExtensionDependency<?> extension;

        private ConditionalDependency(ArtifactKey key, ResolvedArtifact artifact, ExtensionDependency<?> extension) {
            this.key = key;
            this.artifact = artifact;
            this.extension = extension;
        }

        private boolean isConditionSatisfied() {
            if (extension == null || extension.getDependencyConditions().isEmpty()) {
                return true;
            }
            for (var key : extension.getDependencyConditions()) {
                if (!processedDeps.containsKey(key)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class ConditionalDependencyVariant {
        final ExtensionDependency<?> parent;
        final ConditionalDependency conditionalDep;

        private ConditionalDependencyVariant(ExtensionDependency<?> parent, ConditionalDependency conditionalDep) {
            this.parent = parent;
            this.conditionalDep = conditionalDep;
        }
    }
}
