package io.quarkus.devtools.project.state;

import java.util.Objects;

public class ConfiguredBom {

    public static ConfiguredBom enforced(ConfiguredArtifact pom) {
        return new ConfiguredBom(pom, false);
    }

    public static ConfiguredBom enforced(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            boolean local) {
        return new ConfiguredBom(ConfiguredArtifact.pom(groupId, artifactId, version, local), false);
    }

    public static ConfiguredBom imported(ConfiguredValue groupId, ConfiguredValue artifactId, ConfiguredValue version,
            boolean local) {
        return new ConfiguredBom(ConfiguredArtifact.pom(groupId, artifactId, version, local), true);
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
        return (imported ? "imported " : "enforced ") + bom.toCompactString();
    }
}
