package io.quarkus.deployment.builditem;

import java.nio.file.Path;
import java.util.Objects;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.deployment.annotations.PersistentBuildItem;

@PersistentBuildItem
final public class GeneratedSourceCodeBuildItem extends MultiBuildItem {

    private final String language;
    private final Path directory;

    public GeneratedSourceCodeBuildItem(String language, Path directory) {
        this.language = Objects.requireNonNull(language, "language cannot be null");
        this.directory = Objects.requireNonNull(directory, "directory cannot be null");
    }

    public String getLanguage() {
        return language;
    }

    public Path getDirectory() {
        return directory;
    }
}
