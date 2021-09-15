package io.quarkus.bootstrap.model;

import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.DependencyFlags;
import java.io.Serializable;
import java.util.Objects;

public class AppDependency implements Dependency, Serializable {

    private final AppArtifact artifact;
    private final String scope;
    private int flags;

    public AppDependency(AppArtifact artifact, String scope, int... flags) {
        this(artifact, scope, false, flags);
    }

    public AppDependency(AppArtifact artifact, String scope, boolean optional, int... flags) {
        this.artifact = artifact;
        this.scope = scope;
        int tmpFlags = optional ? DependencyFlags.OPTIONAL : 0;
        for (int f : flags) {
            tmpFlags |= f;
        }
        this.flags = tmpFlags;
    }

    @Override
    public AppArtifact getArtifact() {
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
        AppDependency other = (AppDependency) obj;
        return Objects.equals(artifact, other.artifact) && flags == other.flags && Objects.equals(scope, other.scope);
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        artifact.append(buf).append('(');
        if (isDirect()) {
            buf.append("direct ");
        }
        if (isOptional()) {
            buf.append("optional ");
        }
        if (isProjectModule()) {
            buf.append("local ");
        }
        if (isRuntimeExtensionArtifact()) {
            buf.append("extension ");
        }
        if (isRuntimeCp()) {
            buf.append("runtime-cp ");
        }
        if (isDeploymentCp()) {
            buf.append("deployment-cp ");
        }
        return buf.append(scope).append(')').toString();
    }
}
