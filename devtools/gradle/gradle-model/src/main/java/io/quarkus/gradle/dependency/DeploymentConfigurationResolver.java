package io.quarkus.gradle.dependency;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ComponentMetadataDetails;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.tasks.TaskDependencyFactory;
import org.gradle.api.provider.ListProperty;

import io.quarkus.gradle.tooling.dependency.DependencyUtils;
import io.quarkus.gradle.tooling.dependency.ExtensionDependency;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.runtime.LaunchMode;

public class DeploymentConfigurationResolver {

    private static final byte COLLECT_TOP_EXTENSIONS = 0b001;
    private static final String ON = "on";

    public static Attribute<String> getQuarkusDeploymentAttribute(String projectName, LaunchMode mode) {
        var sb = new StringBuilder().append(projectName)
                .append("Quarkus")
                .append(Character.toUpperCase(mode.getDefaultProfile().charAt(0)))
                .append(mode.getDefaultProfile().substring(1))
                .append("DeploymentDependency");
        return Attribute.of(sb.toString(), String.class);
    }

    public static void setDeploymentAndConditionalAttributes(Configuration config, Project project, LaunchMode mode) {
        ConditionalDependencyResolver.setConditionalAttributes(config, project, mode);
        config.attributes(attrs -> selectDeploymentAttribute(attrs, getQuarkusDeploymentAttribute(project.getName(), mode)));
    }

    private static void selectDeploymentAttribute(AttributeContainer attrs, Attribute<String> attr) {
        attrs.attribute(attr, ON);
    }

    public static void registerDeploymentConfiguration(Project project, LaunchMode mode, String configurationName,
            TaskDependencyFactory taskDependencyFactory) {
        project.getConfigurations().register(configurationName,
                config -> new DeploymentConfigurationResolver(project, config, mode, taskDependencyFactory));
    }

    private final Project project;
    private final TaskDependencyFactory taskDependencyFactory;
    private final Attribute<String> quarkusDepAttr;
    private byte walkingFlags;

    private DeploymentConfigurationResolver(Project project, Configuration deploymentConfig, LaunchMode mode,
            TaskDependencyFactory taskDependencyFactory) {
        this.project = project;
        this.taskDependencyFactory = taskDependencyFactory;
        this.quarkusDepAttr = getQuarkusDeploymentAttribute(project.getName(), mode);
        project.getDependencies().getAttributesSchema().attribute(quarkusDepAttr);

        final Configuration baseRuntimeConfig = project.getConfigurations()
                .getByName(ApplicationDeploymentClasspathBuilder.getFinalRuntimeConfigName(mode));
        deploymentConfig.setCanBeConsumed(false);
        deploymentConfig.extendsFrom(baseRuntimeConfig);
        deploymentConfig.shouldResolveConsistentlyWith(baseRuntimeConfig);

        ListProperty<Dependency> dependencyListProperty = project.getObjects().listProperty(Dependency.class);
        final AtomicReference<Collection<Dependency>> directDeploymentDeps = new AtomicReference<>();
        // the following provider appears to be called 3 times for some reason,
        // this is the reason for this atomic reference checks
        deploymentConfig.getDependencies().addAllLater(dependencyListProperty.value(project.provider(() -> {
            Collection<Dependency> directDeps = directDeploymentDeps.get();
            if (directDeps == null) {
                if (!baseRuntimeConfig.getIncoming().getDependencies().isEmpty()) {
                    directDeps = resolveDeploymentDeps(baseRuntimeConfig.getResolvedConfiguration());
                } else {
                    directDeps = List.of();
                }
                directDeploymentDeps.set(directDeps);
            }
            return directDeps;
        })));
        setDeploymentAndConditionalAttributes(deploymentConfig, project, mode);
    }

    private Collection<Dependency> resolveDeploymentDeps(ResolvedConfiguration baseConfig) {
        return initDeploymentVariants(processRuntimeDeps(baseConfig));
    }

    private Map<ArtifactKey, ProcessedDependency> processRuntimeDeps(ResolvedConfiguration baseConfig) {
        final Map<ArtifactKey, ProcessedDependency> allDeps = new HashMap<>();
        setWalkingFlags(COLLECT_TOP_EXTENSIONS);
        for (var dep : baseConfig.getFirstLevelModuleDependencies()) {
            processDependency(null, dep, allDeps);
        }
        walkingFlags = 0;
        return allDeps;
    }

    private void processDependency(ProcessedDependency parent, ResolvedDependency dep,
            Map<ArtifactKey, ProcessedDependency> allDeps) {
        boolean processChildren = false;
        int depFlags = 0;
        var artifacts = dep.getModuleArtifacts();
        ProcessedDependency processedDep = null;
        if (artifacts.isEmpty()) {
            processChildren = true;
        } else {
            for (var artifact : artifacts) {
                processedDep = allDeps.computeIfAbsent(DependencyUtils.getKey(artifact), key -> {
                    final ProcessedDependency pd = new ProcessedDependency(parent, dep,
                            artifact.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier);
                    if (isWalkingFlagsOn(COLLECT_TOP_EXTENSIONS)) {
                        pd.ext = DependencyUtils.getExtensionInfoOrNull(project, artifact);
                        if (pd.ext != null) {
                            pd.flags |= DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT;
                        }
                    }
                    return pd;
                });
                if (processedDep.setFlags(DependencyFlags.VISITED)) {
                    processChildren = true;
                    depFlags |= processedDep.flags;
                }
            }
        }
        if (processChildren) {
            boolean stopCollectingTopExt = isWalkingFlagsOn(COLLECT_TOP_EXTENSIONS)
                    && (depFlags & DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT) > 0;
            if (stopCollectingTopExt) {
                clearWalkingFlags(COLLECT_TOP_EXTENSIONS);
            }
            for (var child : dep.getChildren()) {
                processDependency(processedDep, child, allDeps);
            }
            if (stopCollectingTopExt) {
                setWalkingFlags(COLLECT_TOP_EXTENSIONS);
            }
        }
    }

    private Collection<Dependency> initDeploymentVariants(Map<ArtifactKey, ProcessedDependency> allDeps) {
        final List<Dependency> directDeploymentDeps = new ArrayList<>();
        for (var processedDep : allDeps.values()) {
            if (processedDep.ext != null &&
            // if it's an extension and its deployment artifact is not a runtime dependency (e.g. deployment tests)
                    !allDeps.containsKey(
                            ArtifactKey.of(processedDep.ext.getDeploymentGroup(), processedDep.ext.getDeploymentName(),
                                    ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_JAR))) {
                if (processedDep.hasLocalParent()) {
                    directDeploymentDeps.add(getDeploymentDependency(processedDep.ext));
                } else {
                    project.getDependencies().getComponents().withModule(
                            processedDep.parent.dep.getModule().getId().getModule(),
                            compDetails -> addDeploymentVariant(processedDep.ext, compDetails));
                }
            }
        }
        return directDeploymentDeps;
    }

    private void addDeploymentVariant(ExtensionDependency<?> extDep, ComponentMetadataDetails compDetails) {
        addDeploymentVariant(quarkusDepAttr, compDetails, getDeploymentDependency(extDep));
    }

    static void addDeploymentVariant(Attribute<String> attribute,
            ComponentMetadataDetails compDetails,
            Dependency deploymentDependency) {
        addDeploymentVariant(attribute, compDetails, deploymentDependency, "runtimeElements");
        addDeploymentVariant(attribute, compDetails, deploymentDependency, "runtime");
    }

    private static void addDeploymentVariant(Attribute<String> attribute, ComponentMetadataDetails compDetails,
            Dependency deploymentDependency, String baseVariant) {
        compDetails.maybeAddVariant(attribute.getName(), baseVariant, variant -> {
            variant.attributes(attrs -> selectDeploymentAttribute(attrs, attribute));
            variant.withDependencies(directDeps -> {
                boolean alreadyAdded = false;
                for (var directDep : directDeps) {
                    if (directDep.getName().equals(deploymentDependency.getName()) &&
                            directDep.getGroup().equals(deploymentDependency.getGroup())) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) {
                    directDeps.add(DependencyUtils.asDependencyNotation(deploymentDependency));
                }
            });
        });
    }

    private Dependency getDeploymentDependency(ExtensionDependency<?> ext) {
        return ext.isProjectDependency()
                ? DependencyUtils.createDeploymentProjectDependency((Project) ext.getDeploymentModule(), taskDependencyFactory)
                : DependencyUtils.createDeploymentDependency(project.getDependencies(), ext);
    }

    private boolean isWalkingFlagsOn(byte flags) {
        return (walkingFlags & flags) == flags;
    }

    private void clearWalkingFlags(byte flags) {
        walkingFlags &= (byte) (walkingFlags ^ flags);
    }

    private boolean setWalkingFlags(byte flags) {
        return walkingFlags != (walkingFlags |= flags);
    }

    private static class ProcessedDependency {

        final ProcessedDependency parent;
        final ResolvedDependency dep;
        final boolean local;
        int flags;
        ExtensionDependency<?> ext;

        private ProcessedDependency(ProcessedDependency parent, ResolvedDependency dep, boolean local) {
            this.parent = parent;
            this.dep = dep;
            this.local = local;
        }

        private boolean setFlags(int flags) {
            return this.flags != (this.flags |= flags);
        }

        private boolean hasLocalParent() {
            return parent == null || parent.local;
        }
    }
}
