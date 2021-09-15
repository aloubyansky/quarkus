package io.quarkus.maven.dependency;

public interface Dependency {

    Artifact getArtifact();

    String getScope();

    int getFlags();

    default boolean isOptional() {
        return isFlagSet(DependencyFlags.OPTIONAL);
    }

    default boolean isDirect() {
        return isFlagSet(DependencyFlags.DIRECT);
    }

    default boolean isRuntimeExtensionArtifact() {
        return isFlagSet(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT);
    }

    default boolean isRuntimeCp() {
        return isFlagSet(DependencyFlags.RUNTIME_CP);
    }

    default boolean isDeploymentCp() {
        return isFlagSet(DependencyFlags.DEPLOYMENT_CP);
    }

    default boolean isProjectModule() {
        return isFlagSet(DependencyFlags.PROJECT_MODULE);
    }

    default boolean isReloadable() {
        return isProjectModule() && isFlagSet(DependencyFlags.RELOADABLE);
    }

    default boolean isFlagSet(int flag) {
        return (getFlags() & flag) > 0;
    }
}
