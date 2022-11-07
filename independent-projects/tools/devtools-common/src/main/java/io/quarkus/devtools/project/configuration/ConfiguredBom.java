package io.quarkus.devtools.project.configuration;

import java.nio.file.Path;
import java.util.Objects;

public class ConfiguredBom {

    public static ConfiguredBom enforced(ConfiguredArtifact pom) {
        return new ConfiguredBom(pom, false);
    }

    public static ConfiguredBom enforced(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile, boolean local) {
        return new ConfiguredBom(ConfiguredArtifact.pom(groupId, artifactId, version, configurationFile, local), false);
    }

    public static ConfiguredBom imported(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            Path configurationFile, boolean local) {
        return imported(ConfiguredArtifact.pom(groupId, artifactId, version, configurationFile, local));
    }

    public static ConfiguredBom imported(ConfiguredArtifact pom) {
        return new ConfiguredBom(pom, true);
    }

    private final ConfiguredArtifact bom;
    private final boolean imported;

    private ConfiguredBom(ConfiguredArtifact bom, boolean imported) {
        super();
        this.bom = bom;
        this.imported = imported;
    }

    public ConfiguredArtifact getArtifact() {
        return bom;
    }

    public boolean isImported() {
        return imported;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bom, imported);
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
        return Objects.equals(bom, other.bom) && imported == other.imported;
    }

    @Override
    public String toString() {
        if (imported) {
            return "imports " + bom.toCompactString();
        }
        if (bom.getGroupId().isRawEffective() &&
                bom.getArtifactId().isRawEffective() &&
                bom.getVersion().isRawEffective() && bom.getVersion().getResolvedValue().hasSource()) {
            return "enforced " + bom.toCompactString() + " from " + bom.getVersion().getResolvedValue().getSource();
        }
        return "enforced " + bom.toCompactString();
    }
}
