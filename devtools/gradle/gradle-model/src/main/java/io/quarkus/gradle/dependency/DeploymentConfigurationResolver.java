package io.quarkus.gradle.dependency;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.internal.tasks.TaskDependencyFactory;
import org.gradle.api.provider.ListProperty;

import io.quarkus.gradle.tooling.dependency.DependencyUtils;
import io.quarkus.gradle.tooling.dependency.ExtensionDependency;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.runtime.LaunchMode;

public class DeploymentConfigurationResolver {

    private static final byte COLLECT_TOP_EXTENSIONS = 0b001;

    public static Attribute<String> getQuarkusDeploymentAttribute() {
        return Attribute.of("quarkusDeploymentDependency", String.class);
    }

    public static void registerDeploymentConfiguration(Project project, LaunchMode mode, String configurationName,
            TaskDependencyFactory taskDependencyFactory) {
        project.getConfigurations().register(configurationName,
                config -> new DeploymentConfigurationResolver(project, config, mode, taskDependencyFactory));
    }

    private final Project project;
    private TaskDependencyFactory taskDependencyFactory;
    private final String depVariantValue;
    private final Attribute<String> quarkusDepAttr = getQuarkusDeploymentAttribute();
    private byte walkingFlags;

    private DeploymentConfigurationResolver(Project project, Configuration deploymentConfig, LaunchMode mode,
            TaskDependencyFactory taskDependencyFactory) {
        this.project = project;
        this.taskDependencyFactory = taskDependencyFactory;
        this.depVariantValue = deploymentConfig.getName();

        System.out.println("registerDeploymentConfiguration " + project.getPath() + " " + deploymentConfig.getName());
        final Configuration baseRuntimeConfig = project.getConfigurations()
                .getByName(ConditionalDependencyResolver.getConfigurationName(mode));
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
        deploymentConfig.attributes(attrs -> {
            attrs.attribute(quarkusDepAttr, depVariantValue);
            attrs.attribute(ConditionalDependencyResolver.getQuarkusConditionalDependencyAttribute(), depVariantValue);
        });
    }

    private Collection<Dependency> resolveDeploymentDeps(ResolvedConfiguration baseConfig) {
        return initDeploymentVariants(processRuntimeDeps(baseConfig));
    }

    private Map<ArtifactKey, ProcessedDependency> processRuntimeDeps(ResolvedConfiguration baseConfig) {
        final Map<ArtifactKey, ProcessedDependency> allDeps = new HashMap<>();
        for (var dep : baseConfig.getFirstLevelModuleDependencies()) {
            processDependency(null, dep, allDeps);
        }
        return allDeps;
    }

    private static Set<ArtifactKey> getProjectKeys(Project rootProject) {
        var projectKeys = new HashSet<ArtifactKey>();
        collectProjectKeys(rootProject, projectKeys);
        return projectKeys;
    }

    private static void collectProjectKeys(Project project, Set<ArtifactKey> projectKeys) {
        if (projectKeys.add(ArtifactKey.ga(String.valueOf(project.getGroup()), project.getName()))) {
            for (var c : project.getSubprojects()) {
                collectProjectKeys(c, projectKeys);
            }
        }
    }

    private Collection<Dependency> initDeploymentVariants(Map<ArtifactKey, ProcessedDependency> allDeps) {
        final List<Dependency> directDeploymentDeps = new ArrayList<>();
        var projectKeys = getProjectKeys(project.getRootProject());
        for (var processedDep : allDeps.values()) {
            if (processedDep.ext != null) {
                System.out.println(
                        "Extension " + DependencyUtils.getKey(processedDep.dep) + ", direct=" + (processedDep.parent == null));
                if (processedDep.parent == null) {
                    directDeploymentDeps.add(getDeploymentDependency(processedDep.ext));
                } else if (projectKeys.contains(
                        ArtifactKey.ga(processedDep.parent.getModuleGroup(), processedDep.parent.getModuleName()))) {
                    // this is unfortunate but the ComponentMetadataDetails approach doesn't work for local projects
                    // these deployment dependencies will be added as direct application dependencies
                    directDeploymentDeps.add(getDeploymentDependency(processedDep.ext));
                } else {
                    project.getDependencies().getComponents().withModule(processedDep.parent.getModule().getId().getModule(),
                            compDetails -> {
                                compDetails.addVariant("quarkus.variant." + depVariantValue, "compile", variant -> {
                                    variant.attributes(attrs -> {
                                        attrs.attribute(quarkusDepAttr, depVariantValue);
                                        System.out.println("Adding variant " + depVariantValue + " of " + compDetails.getId());
                                    });
                                    variant.withDependencies(directDeps -> {
                                        boolean alreadyAdded = false;
                                        for (var directDep : directDeps) {
                                            if (directDep.getName().equals(processedDep.ext.getDeploymentName()) &&
                                                    directDep.getGroup().equals(processedDep.ext.getDeploymentGroup())) {
                                                alreadyAdded = true;
                                                break;
                                            }
                                        }
                                        if (!alreadyAdded) {
                                            directDeps.add(DependencyUtils
                                                    .asDependencyNotation(getDeploymentDependency(processedDep.ext)));
                                        }
                                    });
                                });
                            });
                }
            }
        }
        return directDeploymentDeps;
    }

    private Dependency getDeploymentDependency(ExtensionDependency<?> ext) {
        if (ext.isProjectDependency()) {
            return DependencyUtils.createDeploymentProjectDependency((Project) ext.getDeploymentModule(), "runtimeElements",
                    taskDependencyFactory);
        }
        return DependencyUtils.createDeploymentDependency(project.getDependencies(), ext);
    }

    private void processDependency(ResolvedDependency parent, ResolvedDependency dep,
            Map<ArtifactKey, ProcessedDependency> allDeps) {
        boolean processChildren = false;
        int depFlags = 0;
        for (var artifact : dep.getModuleArtifacts()) {
            var processedDep = allDeps.computeIfAbsent(DependencyUtils.getKey(artifact), key -> {
                final ProcessedDependency pd = new ProcessedDependency(parent, artifact);
                if (!isWalkingFlagsOn(COLLECT_TOP_EXTENSIONS)) {
                    pd.ext = DependencyUtils.getExtensionInfoOrNull(project, artifact);
                    if (pd.ext != null) {
                        pd.flags |= DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT;
                        setWalkingFlags(COLLECT_TOP_EXTENSIONS);
                    }
                }
                return pd;
            });
            if (processedDep.setFlags(DependencyFlags.VISITED)) {
                processChildren = true;
                depFlags |= processedDep.flags;
            }
        }
        if (processChildren) {
            for (var child : dep.getChildren()) {
                processDependency(dep, child, allDeps);
            }
            if ((depFlags & DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT) > 0) {
                clearWalkingFlags(COLLECT_TOP_EXTENSIONS);
            }
        }
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

        final ResolvedDependency parent;
        final ResolvedArtifact dep;
        int flags;
        ExtensionDependency<?> ext;

        private ProcessedDependency(ResolvedDependency parent, ResolvedArtifact dep) {
            this.parent = parent;
            this.dep = dep;
        }

        private boolean setFlags(int flags) {
            return this.flags != (this.flags |= flags);
        }
    }
}
