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
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.internal.tasks.TaskDependencyFactory;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;

import io.quarkus.gradle.tooling.dependency.DependencyUtils;
import io.quarkus.gradle.tooling.dependency.ExtensionDependency;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.runtime.LaunchMode;

public class ConditionalDependencyResolver {

    private static final String ON = "on";
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

    public static void setConditionalAttributes(Configuration config, Project project, LaunchMode mode) {
        config.attributes(attrs -> {
            final ObjectFactory objectFactory = project.getObjects();
            attrs.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.LIBRARY));
            attrs.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
            attrs.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objectFactory.named(LibraryElements.class, LibraryElements.JAR));
            attrs.attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EXTERNAL));
            attrs.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    objectFactory.named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
            attrs.attribute(getQuarkusConditionalDependencyAttribute(project.getName(), mode), ON);
        });
    }

    public static void resolve(Project project, LaunchMode mode, TaskDependencyFactory taskDependencyFactory) {
        new ConditionalDependencyResolver(project, mode, taskDependencyFactory).resolve();
    }

    private final Attribute<String> quarkusDepAttr;
    private final Project project;
    private final Map<ArtifactKey, ProcessedDependency> processedDeps = new HashMap<>();
    private final Map<ArtifactKey, ConditionalDependency> allConditionalDeps = new HashMap<>();
    private final List<ConditionalDependencyVariant> dependencyVariantQueue = new ArrayList<>();
    private final Map<String, SatisfiedExtensionDeps> satisfiedExtensionDeps = new HashMap<>();
    private final LaunchMode mode;
    private final TaskDependencyFactory taskDependencyFactory;
    private final AtomicInteger configCopyCounter = new AtomicInteger();

    private ConditionalDependencyResolver(Project project, LaunchMode mode, TaskDependencyFactory taskDependencyFactory) {
        this.project = project;
        this.mode = mode;
        this.quarkusDepAttr = getQuarkusConditionalDependencyAttribute(project.getName(), mode);
        project.getDependencies().getAttributesSchema().attribute(quarkusDepAttr);
        this.taskDependencyFactory = taskDependencyFactory;
    }

    private Configuration getBaseConfiguration() {
        return project.getConfigurations().getByName(ApplicationDeploymentClasspathBuilder.getBaseRuntimeConfigName(mode));
    }

    private void resolve() {
        project.getConfigurations().resolvable(
                getConfigurationName(mode),
                config -> {
                    config.setCanBeConsumed(false);
                    config.extendsFrom(getBaseConfiguration());
                    setConditionalAttributes(config, project, mode);
                    final ListProperty<Dependency> dependencyProperty = project.getObjects().listProperty(Dependency.class);
                    final AtomicInteger invocations = new AtomicInteger();
                    config.getDependencies().addAllLater(dependencyProperty.value(project.provider(() -> {
                        if (invocations.getAndIncrement() == 0) {
                            activateConditionalDeps(getBaseConfiguration());
                        }
                        return Set.of();
                    })));
                });
    }

    private void activateConditionalDeps(Configuration baseConfig) {
        processConfiguration(baseConfig);
        while (!dependencyVariantQueue.isEmpty()) {
            boolean satisfiedConditions = false;
            var i = dependencyVariantQueue.iterator();
            while (i.hasNext()) {
                var conditionalVariant = i.next();
                if (conditionalVariant.conditionalDep.isConditionSatisfied()) {
                    satisfiedConditions = true;
                    i.remove();

                    satisfiedExtensionDeps.computeIfAbsent(conditionalVariant.parent.toModuleName(),
                            key -> new SatisfiedExtensionDeps(conditionalVariant.parent)).deps
                            .add(conditionalVariant.conditionalDep);
                }
            }
            if (!satisfiedConditions) {
                break;
            }
            processConfiguration(baseConfig);
        }

        for (var satisfiedDeps : satisfiedExtensionDeps.values()) {
            project.getDependencies().getComponents().withModule(
                    satisfiedDeps.parent.getGroup() + ":" + satisfiedDeps.parent.getName(),
                    compDetails -> addConditionalDependencyVariant(compDetails, satisfiedDeps.deps));

            for (var satisfiedDep : satisfiedDeps.deps) {
                if (satisfiedDep.extension != null) {
                    // add the corresponding deployment variant
                    project.getDependencies().getComponents().withModule(
                            satisfiedDeps.parent.getDeploymentGroup() + ":"
                                    + satisfiedDeps.parent.getDeploymentName(),
                            compDetails -> DeploymentConfigurationResolver.addDeploymentVariant(
                                    DeploymentConfigurationResolver.getQuarkusDeploymentAttribute(project.getName(), mode),
                                    compDetails, getDeploymentDependency(satisfiedDep.extension)));
                }
            }
        }
    }

    private void addConditionalDependencyVariant(ComponentMetadataDetails compDetails, List<ConditionalDependency> deps) {
        addConditionalDependencyVariant(compDetails, deps, RUNTIME_ELEMENTS);
        addConditionalDependencyVariant(compDetails, deps, RUNTIME);
    }

    private void addConditionalDependencyVariant(ComponentMetadataDetails compDetails,
            List<ConditionalDependency> satisfiedDeps, String baseVariant) {
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
                        for (var satisfiedDep : satisfiedDeps) {
                            boolean alreadyAdded = false;
                            for (var directDep : directDeps) {
                                if (directDep.getName().equals(satisfiedDep.key.getArtifactId())
                                        && directDep.getGroup().equals(satisfiedDep.key.getGroupId())) {
                                    alreadyAdded = true;
                                    break;
                                }
                            }
                            if (!alreadyAdded) {
                                var a = satisfiedDep.artifact;
                                directDeps.add(DependencyUtils.asDependencyNotation(project.getDependencyFactory().create(
                                        a.getModuleVersion().getId().getGroup(),
                                        a.getName(),
                                        a.getModuleVersion().getId().getVersion(),
                                        a.getClassifier(), a.getExtension())));
                            }
                        }
                    });
                });
    }

    private Configuration copyConfig(Configuration baseConfig) {
        return project.getConfigurations()
                .resolvable(baseConfig.getName() + "Copy" + configCopyCounter.incrementAndGet(), c -> {
                    c.setCanBeConsumed(false);
                    c.extendsFrom(baseConfig);
                    setConditionalAttributes(c, project, mode);
                }).get();
    }

    private void selectConditionalAttribute(AttributeContainer attrs) {
        attrs.attribute(quarkusDepAttr, ON);
    }

    private void processConfiguration(Configuration baseConfig) {
        var config = copyConfig(baseConfig);
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
        setConditionalAttributes(config, project, mode);
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

    private record ConditionalDependencyVariant(ExtensionDependency<?> parent, ConditionalDependency conditionalDep) {
    }

    private static class SatisfiedExtensionDeps {

        final ExtensionDependency<?> parent;
        final List<ConditionalDependency> deps = new ArrayList<>(2);

        private SatisfiedExtensionDeps(ExtensionDependency<?> parent) {
            this.parent = parent;
        }
    }
}
