package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.Objects;

public class ConfiguredBom {

    public static ConfiguredBom enforced(ConfiguredArtifact pom) {
        return new ConfiguredBom(pom, true);
    }

    public static ConfiguredBom enforced(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile, boolean local) {
        return new ConfiguredBom(ConfiguredArtifact.pom(groupId, artifactId, version, configurationFile, local), true);
    }

    public static ConfiguredBom imported(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile, boolean local) {
        return imported(ConfiguredArtifact.pom(groupId, artifactId, version, configurationFile, local));
    }

    public static ConfiguredBom imported(ConfiguredArtifact pom) {
        return new ConfiguredBom(pom, false);
    }

    private final ConfiguredArtifact bom;
    private final boolean enforcedExternally;

    private ConfiguredBom(ConfiguredArtifact bom, boolean enforcedExternally) {
        super();
        this.bom = bom;
        this.enforcedExternally = enforcedExternally;
    }

    public ConfiguredArtifact getArtifact() {
        return bom;
    }

    public boolean isEnforcedExternally() {
        return enforcedExternally;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bom, enforcedExternally);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ConfiguredBom other = (ConfiguredBom) obj;
        return Objects.equals(bom, other.bom) && enforcedExternally == other.enforcedExternally;
    }

    @Override
    public String toString() {
        if (enforcedExternally) {
            var sb = new StringBuilder().append(bom.toCompactString()).append(" is enforced externally");
            if (bom.getConfigurationFile() != null) {
                sb.append(" from ").append(bom.getConfigurationFile());
            }
            return sb.toString();
        }
        return bom.toCompactString() + " is imported in " + bom.getConfigurationFile();
    }
}
