package io.quarkus.devtools.project.configuration.update;

import java.nio.file.Path;

import io.quarkus.devtools.project.configuration.ConfiguredArtifact;
import io.quarkus.maven.dependency.ArtifactCoords;

class MavenPluginConfig {

    private final Path configurationFile;
    private final ConfiguredArtifact configuredPlugin;
    private String recommendedGroupId;
    private String recommendedArtifactId;
    private String recommendedVersion;

    MavenPluginConfig(Path configurationFile, ConfiguredArtifact configuredPlugin) {
        this.configurationFile = configurationFile;
        this.configuredPlugin = configuredPlugin;
    }

    void setRecommendedGroupId(String recommendedGroupId) {
        this.recommendedGroupId = recommendedGroupId;
    }

    void setRecommendedArtifactId(String recommendedArtifactId) {
        this.recommendedArtifactId = recommendedArtifactId;
    }

    void setRecommendedVersion(String recommendedVersion) {
        this.recommendedVersion = recommendedVersion;
    }

    void addUpdateStep(UpdateInstructions actions) {
        actions.add(MavenPluginInstruction.update(configurationFile,
                configuredPlugin.getRawCoords(),
                ArtifactCoords.jar(
                        recommendedGroupId == null ? configuredPlugin.getGroupId().getRawValue() : recommendedGroupId,
                        recommendedArtifactId == null ? configuredPlugin.getArtifactId().getRawValue() : recommendedArtifactId,
                        recommendedVersion == null ? configuredPlugin.getVersion().getRawValue() : recommendedVersion)));
    }
}
