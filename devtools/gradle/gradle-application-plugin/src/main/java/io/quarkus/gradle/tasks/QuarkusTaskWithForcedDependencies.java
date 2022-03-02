package io.quarkus.gradle.tasks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;

import io.quarkus.gradle.dependency.ApplicationDeploymentClasspathBuilder;
import io.quarkus.gradle.tooling.ToolingUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.runtime.LaunchMode;

public abstract class QuarkusTaskWithForcedDependencies extends QuarkusTask {

    QuarkusTaskWithForcedDependencies(String description) {
        super(description);
        configure();
    }

    private void configure() {
        final Configuration config = getProject().getConfigurations()
                .getByName(ApplicationDeploymentClasspathBuilder.getBaseRuntimeConfigName(LaunchMode.NORMAL));
        config.withDependencies(deps -> {
            final DependencyHandler depHandler = getProject().getDependencies();
            for (ArtifactCoords coords : normalize(getForcedDependencies(), config)) {
                final Dependency forced = depHandler.create(
                        coords.getGroupId() + ":" + coords.getArtifactId() + ":" + coords.getVersion());
                deps.add(forced);
            }
        });
    }

    private List<GACTV> normalize(List<GACTV> originalCoords, Configuration configuration) {
        // Make sure the artifact coordinates have proper versions
        List<GACTV> normalized = null;
        Map<ArtifactKey, String> constraints = null;
        for (int i = 0; i < originalCoords.size(); ++i) {
            final GACTV coords = originalCoords.get(i);
            if (coords.getVersion() == null || "managed".equals(coords.getVersion())) {
                if (normalized == null) {
                    normalized = new ArrayList<>(originalCoords.size());
                    for (int j = 0; j < i; ++j) {
                        normalized.add(originalCoords.get(j));
                    }
                }

                if (constraints == null) {
                    constraints = getConstraints(configuration);
                }

                final String version = constraints.get(coords.getKey());
                if (version == null) {
                    throw new GradleException("Failed to locate " + coords.getKey() + " among the project's constraints");
                }
                normalized.add(new GACTV(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(), coords.getType(),
                        version));
            } else if (normalized != null) {
                normalized.add(coords);
            }
        }
        return normalized == null ? originalCoords : normalized;
    }

    private Map<ArtifactKey, String> getConstraints(Configuration configuration) {
        final List<Dependency> enforcedPlatforms = ToolingUtils.getEnforcedPlatforms(configuration);
        if (enforcedPlatforms.isEmpty()) {
            return Map.of();
        }
        final Map<ArtifactKey, String> constraints = new HashMap<>();
        final Configuration constraintsConfig = getProject().getConfigurations()
                .detachedConfiguration(enforcedPlatforms.toArray(new Dependency[0]));
        constraintsConfig.getResolutionStrategy().eachDependency(d -> {
            constraints.put(ArtifactKey.gact(d.getTarget().getGroup(), d.getTarget().getName(), null, ArtifactCoords.TYPE_JAR),
                    d.getTarget().getVersion());
        });
        constraintsConfig.resolve();
        return constraints;
    }

    /**
     * Dependencies this task is forcing on the build. Typically it would be dependencies
     * that aren't present in the original configuration.
     * 
     * TODO this method could be accepting an argument of type {@link io.quarkus.runtime.LaunchMode}
     * 
     * @return dependencies this task is forcing on the build
     */
    public abstract List<GACTV> getForcedDependencies();
}
