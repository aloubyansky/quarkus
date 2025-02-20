package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;

public class ConfiguredArtifact {

    public static ConfiguredArtifact jar(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile) {
        return jar(groupId, artifactId, version, configurationFile, false);
    }

    public static ConfiguredArtifact jar(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile, boolean local) {
        return of(groupId, artifactId, ConfiguredValue.of(ArtifactCoords.DEFAULT_CLASSIFIER),
                ConfiguredValue.of(ArtifactCoords.TYPE_JAR), version, configurationFile, local);
    }

    public static ConfiguredArtifact pom(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile) {
        return pom(groupId, artifactId, version, configurationFile, false);
    }

    public static ConfiguredArtifact pom(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile, boolean local) {
        return of(groupId, artifactId, ConfiguredValue.of(ArtifactCoords.DEFAULT_CLASSIFIER),
                ConfiguredValue.of(ArtifactCoords.TYPE_POM), version, configurationFile, local);
    }

    public static ConfiguredArtifact of(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue classifier,
            ConfiguredValue type, ConfiguredValue version, Path configurationFile) {
        return of(groupId, artifactId, classifier, type, version, configurationFile, false);
    }

    public static ConfiguredArtifact of(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue classifier,
            ConfiguredValue type, ConfiguredValue version, Path configurationFile, boolean local) {
        return new ConfiguredArtifact(groupId, artifactId, classifier, type, version, configurationFile, local);
    }

    private final ConfiguredValue groupId;
    private final ConfiguredValue artifactId;
    private final ConfiguredValue classifier;
    private final ConfiguredValue type;
    private final ConfiguredValue version;
    private final Path configurationFile;
    private final boolean local;
    private ArtifactKey key;

    private ConfiguredArtifact(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue classifier,
            ConfiguredValue type, ConfiguredValue version, Path configurationFile, boolean local) {
        super();
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.classifier = classifier;
        this.type = type;
        this.version = version;
        this.configurationFile = configurationFile;
        this.local = local;
    }

    public ConfiguredValue getGroupId() {
        return groupId;
    }

    public ConfiguredValue getArtifactId() {
        return artifactId;
    }

    public ConfiguredValue getClassifier() {
        return classifier;
    }

    public ConfiguredValue getType() {
        return type;
    }

    public ConfiguredValue getVersion() {
        return version;
    }

    public Path getConfigurationFile() {
        return configurationFile;
    }

    public boolean isLocal() {
        return local;
    }

    public boolean isManagedVersion() {
        return version.getRawValue() == null || version.getRawValue().isEmpty();
    }

    public ArtifactCoords getRawCoords() {
        return ArtifactCoords.of(groupId.getRawValue(), artifactId.getRawValue(), classifier.getRawValue(),
                type.getRawValue(), version.getRawValue());
    }

    public ArtifactCoords getEffectiveCoords() {
        return ArtifactCoords.of(groupId.getEffectiveValue(), artifactId.getEffectiveValue(), classifier.getEffectiveValue(),
                type.getEffectiveValue(), version.getEffectiveValue());
    }

    public ArtifactKey getKey() {
        return key == null
                ? key = ArtifactKey.of(groupId.getEffectiveValue(), artifactId.getEffectiveValue(),
                        classifier.getEffectiveValue(), type.getEffectiveValue())
                : key;
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactId, classifier, groupId, local, type, version);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ConfiguredArtifact other = (ConfiguredArtifact) obj;
        return Objects.equals(artifactId, other.artifactId) && Objects.equals(classifier, other.classifier)
                && Objects.equals(groupId, other.groupId) && local == other.local
                && Objects.equals(type, other.type) && Objects.equals(version, other.version);
    }

    public String toCompactString() {
        final StringBuilder sb = new StringBuilder()
                .append(groupId).append(':')
                .append(artifactId).append(':');
        if (!classifier.isEffectivelyNull() && !classifier.getEffectiveValue().isEmpty()) {
            sb.append(classifier).append(':');
        }
        if (type.isEffectivelyNull() && !ArtifactCoords.TYPE_JAR.equals(type.getEffectiveValue())) {
            sb.append(type).append(':');
        }
        sb.append(version);
        if (local) {
            sb.append("[local]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder()
                .append(groupId).append(':')
                .append(artifactId).append(':')
                .append(classifier == null || classifier.getRawValue() == null ? "" : classifier).append(':')
                .append(type).append(':')
                .append(version);
        if (local) {
            sb.append("[local]");
        }
        return sb.toString();
    }
}
