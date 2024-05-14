package io.quarkus.deployment.pkg.builditem;

import static io.quarkus.deployment.pkg.PackageConfig.JarConfig.JarType.*;

import java.nio.file.Path;

import io.quarkus.bootstrap.app.JarResult;
import io.quarkus.bootstrap.app.PackagedApplication;
import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;

public final class JarBuildItem extends SimpleBuildItem {

    private final Path path;
    private final Path originalArtifact;
    private final Path libraryDir;
    private final PackageConfig.JarConfig.JarType type;
    private final String classifier;
    private final PackagedApplication packagedApplication;

    public JarBuildItem(Path path, Path originalArtifact, Path libraryDir, PackageConfig.JarConfig.JarType type,
            String classifier) {
        this(path, originalArtifact, libraryDir, type, classifier, null);
    }

    public JarBuildItem(Path path, Path originalArtifact, Path libraryDir, PackageConfig.JarConfig.JarType type,
            String classifier, PackagedApplication packagedApplication) {
        this.path = path;
        this.originalArtifact = originalArtifact;
        this.libraryDir = libraryDir;
        this.type = type;
        this.classifier = classifier;
        this.packagedApplication = packagedApplication;
    }

    public boolean isUberJar() {
        return libraryDir == null;
    }

    public Path getPath() {
        return path;
    }

    public Path getLibraryDir() {
        return libraryDir;
    }

    public Path getOriginalArtifact() {
        return originalArtifact;
    }

    public PackageConfig.JarConfig.JarType getType() {
        return type;
    }

    public String getClassifier() {
        return classifier;
    }

    public PackagedApplication getPackagedApplication() {
        return packagedApplication;
    }

    public JarResult toJarResult() {
        return new JarResult(path, originalArtifact, libraryDir, type == MUTABLE_JAR,
                classifier);
    }
}
