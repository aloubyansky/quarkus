package io.quarkus.gradle.dependency;

import static io.quarkus.gradle.tooling.dependency.DependencyUtils.getKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ComponentMetadataDetails;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.tasks.TaskDependencyFactory;
import org.gradle.api.provider.ListProperty;
import org.jetbrains.annotations.NotNull;

import io.quarkus.gradle.tooling.dependency.DependencyUtils;
import io.quarkus.gradle.tooling.dependency.ExtensionDependency;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.runtime.LaunchMode;

public class ConditionalDependencyResolver {

    private static final String ON = "on";
    private static final String OFF = "off";
    private static final String RUNTIME_ELEMENTS = "runtimeElements";
    private static final String RUNTIME = "runtime";

    public static String getConfigurationName(LaunchMode mode) {
        return "conditional" + ApplicationDeploymentClasspathBuilder.getLaunchModeAlias(mode)
                + "RuntimeConfiguration";
    }

    public static Attribute<String> getQuarkusConditionalDependencyAttribute(String projectName, LaunchMode mode) {
        var sb = new StringBuilder().append(projectName)
                .append("Quarkus")
                .append(Character.toUpperCase(mode.getDefaultProfile().charAt(0)))
                .append(mode.getDefaultProfile().substring(1))
                .append("ConditionalDependency");
        return Attribute.of(sb.toString(), String.class);
    }

    public static void setConditionalAttributes(AttributeContainer attributes, String appName, LaunchMode mode) {
        attributes.attribute(getQuarkusConditionalDependencyAttribute(appName, mode), ON);
    }

    public static void resolve(Project project, LaunchMode mode, TaskDependencyFactory taskDependencyFactory) {
        new ConditionalDependencyResolver(project, mode, taskDependencyFactory).resolve(
                project.getConfigurations().getByName(ApplicationDeploymentClasspathBuilder.getBaseRuntimeConfigName(mode)),
                mode);
    }

    private final Attribute<String> quarkusDepAttr;
    private final Project project;
    private final Map<ArtifactKey, ProcessedDependency> processedDeps = new HashMap<>();
    private final Map<ArtifactKey, ConditionalDependency> allConditionalDeps = new HashMap<>();
    private final List<ConditionalDependencyVariant> dependencyVariantQueue = new ArrayList<>();
    private final LaunchMode mode;
    private final TaskDependencyFactory taskDependencyFactory;

    private ConditionalDependencyResolver(Project project, LaunchMode mode, TaskDependencyFactory taskDependencyFactory) {
        this.project = project;
        this.mode = mode;
        this.quarkusDepAttr = getQuarkusConditionalDependencyAttribute(project.getName(), mode);
        this.taskDependencyFactory = taskDependencyFactory;
    }

    private void resolve(Configuration baseConfig, LaunchMode mode) {

        final String configName = getConfigurationName(mode);
        project.getConfigurations().register(
                configName,
                config -> {
                    config.extendsFrom(baseConfig);
                    config.attributes(this::selectConditionalAttribute);
                    final ListProperty<Dependency> dependencyProperty = project.getObjects().listProperty(Dependency.class);
                    final AtomicInteger invocations = new AtomicInteger();
                    config.getDependencies().addAllLater(dependencyProperty.value(project.provider(() -> {
                        if (invocations.getAndIncrement() == 0) {
                            activateConditionalDeps(baseConfig);
                        }
                        return Set.of();
                    })));
                });
    }

    private void activateConditionalDeps(Configuration baseConfig) {
        processConfiguration(copyConfig(baseConfig));
        while (!dependencyVariantQueue.isEmpty()) {
            boolean satisfiedConditions = false;
            var i = dependencyVariantQueue.iterator();
            while (i.hasNext()) {
                var conditionalVariant = i.next();
                if (conditionalVariant.conditionalDep.isConditionSatisfied()) {
                    satisfiedConditions = true;
                    i.remove();

                    project.getDependencies().getComponents().withModule(
                            conditionalVariant.parent.getGroup() + ":" + conditionalVariant.parent.getName(),
                            compDetails -> addConditionalDependencyVariant(compDetails, conditionalVariant));

                    if (conditionalVariant.conditionalDep.extension != null) {
                        // add the corresponding deployment variant
                        project.getDependencies().getComponents().withModule(
                                conditionalVariant.parent.getDeploymentGroup() + ":"
                                        + conditionalVariant.parent.getDeploymentName(),
                                compDetails -> DeploymentConfigurationResolver.addDeploymentVariant(
                                        DeploymentConfigurationResolver.getQuarkusDeploymentAttribute(project.getName(), mode),
                                        compDetails, getDeploymentDependency(conditionalVariant.conditionalDep.extension)));
                    }
                }
            }
            if (!satisfiedConditions) {
                break;
            }
            processConfiguration(copyConfig(baseConfig));
        }
    }

    private void addConditionalDependencyVariant(ComponentMetadataDetails compDetails,
            ConditionalDependencyVariant conditionalVariant) {
        addConditionalDependencyVariant(compDetails, conditionalVariant, RUNTIME_ELEMENTS);
        addConditionalDependencyVariant(compDetails, conditionalVariant, RUNTIME);
    }

    private void addConditionalDependencyVariant(ComponentMetadataDetails compDetails,
            ConditionalDependencyVariant conditionalVariant, String baseVariant) {
        compDetails.maybeAddVariant(
                quarkusDepAttr.getName(),
                baseVariant,
                variant -> {
                    final AtomicInteger selectCounter = new AtomicInteger();
                    variant.attributes(attrs -> {
                        if (selectCounter.getAndIncrement() == 0) {
                            selectConditionalAttribute(attrs);
                        }
                    });
                    variant.withDependencies(directDeps -> {
                        boolean alreadyAdded = false;
                        for (var directDep : directDeps) {
                            if (directDep.getName().equals(conditionalVariant.conditionalDep.key.getArtifactId())
                                    && directDep.getGroup().equals(conditionalVariant.conditionalDep.key.getGroupId())) {
                                alreadyAdded = true;
                                break;
                            }
                        }
                        if (!alreadyAdded) {
                            var a = conditionalVariant.conditionalDep.artifact;
                            directDeps.add(DependencyUtils.asDependencyNotation(project.getDependencyFactory().create(
                                    a.getModuleVersion().getId().getGroup(),
                                    a.getName(),
                                    a.getModuleVersion().getId().getVersion(),
                                    a.getClassifier(), a.getExtension())));
                        }
                    });
                });
    }

    private @NotNull Configuration copyConfig(Configuration baseConfig) {
        var configCopy = baseConfig.copyRecursive();
        configCopy.attributes(this::selectConditionalAttribute);
        return configCopy;
    }

    private void selectConditionalAttribute(AttributeContainer attrs) {
        attrs.attribute(quarkusDepAttr, ON);
        if (mode != LaunchMode.DEVELOPMENT) {
            attrs.attribute(getQuarkusConditionalDependencyAttribute(project.getName(), LaunchMode.DEVELOPMENT), OFF);
        }
        if (mode != LaunchMode.TEST) {
            attrs.attribute(getQuarkusConditionalDependencyAttribute(project.getName(), LaunchMode.TEST), OFF);
        }
        if (mode != LaunchMode.NORMAL) {
            attrs.attribute(getQuarkusConditionalDependencyAttribute(project.getName(), LaunchMode.NORMAL), OFF);
        }
    }

    private void processConfiguration(Configuration config) {
        final Set<ModuleVersionIdentifier> visited = new HashSet<>();
        for (var dep : config.getResolvedConfiguration().getFirstLevelModuleDependencies()) {
            processDependency(dep, visited);
        }
    }

    private void processDependency(ResolvedDependency dep, Set<ModuleVersionIdentifier> visited) {
        if (!visited.add(dep.getModule().getId())) {
            return;
        }
        var artifacts = dep.getModuleArtifacts();
        if (!artifacts.isEmpty()) {
            for (var a : artifacts) {
                processedDeps.computeIfAbsent(getKey(a), key -> {
                    var processedDep = new ProcessedDependency(DependencyUtils.getExtensionInfoOrNull(project, a));
                    processedDep.queueConditionalDeps();
                    return processedDep;
                });
            }
        }
        for (var c : dep.getChildren()) {
            processDependency(c, visited);
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
        final Configuration config = project.getConfigurations().detachedConfiguration(dep).setTransitive(false);
        config.attributes(this::selectConditionalAttribute);
        for (var a : config.getResolvedConfiguration().getResolvedArtifacts()) {
            return new ConditionalDependency(getKey(a), a,
                    DependencyUtils.getExtensionInfoOrNull(project, a));
        }
        throw new RuntimeException(dep + " did not resolve to any artifacts");
    }

    private Dependency getDeploymentDependency(ExtensionDependency<?> ext) {
        return ext.isProjectDependency()
                ? DependencyUtils.createDeploymentProjectDependency((Project) ext.getDeploymentModule(), taskDependencyFactory)
                : DependencyUtils.createDeploymentDependency(project.getDependencies(), ext);
    }

    private class ProcessedDependency {
        private final ExtensionDependency<?> extension;

        private ProcessedDependency(ExtensionDependency<?> extension) {
            this.extension = extension;
        }

        private void queueConditionalDeps() {
            if (extension == null) {
                return;
            }
            for (var dep : extension.getConditionalDependencies()) {
                queueConditionalDependency(this, dep);
            }
            if (mode == LaunchMode.DEVELOPMENT) {
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
