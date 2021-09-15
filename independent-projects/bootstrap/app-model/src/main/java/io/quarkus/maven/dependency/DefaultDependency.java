package io.quarkus.maven.dependency;

import java.io.Serializable;
import java.util.Objects;

public class DefaultDependency implements Dependency, Serializable {

    private final Artifact artifact;
    private final String scope;
    private int flags;

    public DefaultDependency(Artifact artifact, int... flags) {
        this(artifact, "compile", flags);
    }

    public DefaultDependency(Artifact artifact, String scope, int... flags) {
        this.artifact = artifact;
        this.scope = scope;
        int allFlags = 0;
        for (int f : flags) {
            allFlags |= f;
        }
        this.flags = allFlags;
    }

    @Override
    public Artifact getArtifact() {
        return artifact;
    }

    @Override
    public String getScope() {
        return scope;
    }

    @Override
    public int getFlags() {
        return flags;
    }

    public void setFlag(int flag) {
        flags |= flag;
    }

    public void clearFlag(int flag) {
        if ((flags & flag) > 0) {
            flags ^= flag;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifact, flags, scope);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DefaultDependency other = (DefaultDependency) obj;
        return Objects.equals(artifact, other.artifact) && flags == other.flags && Objects.equals(scope, other.scope);
    }

    @Override
    public String toString() {
        return "[" + artifact + " " + scope + "]";
    }
}
