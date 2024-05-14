package io.quarkus.bootstrap.app;

import java.nio.file.Path;
import java.util.Map;

public class ArtifactResult {

    private final Path path;
    private final String type;
    private final Map<String, String> metadata;
    private final PackagedApplication packagedApplication;

    public ArtifactResult(Path path, String type, Map<String, String> metadata) {
        this(path, type, metadata, null);
    }

    public ArtifactResult(Path path, String type, Map<String, String> metadata, PackagedApplication packagedApplication) {
        this.path = path;
        this.type = type;
        this.metadata = metadata;
        this.packagedApplication = packagedApplication;
    }

    public Path getPath() {
        return path;
    }

    public String getType() {
        return type;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public PackagedApplication getPackagedApplication() {
        return packagedApplication;
    }
}
