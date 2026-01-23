package io.quarkus.deployment.builditem;

import java.nio.file.Path;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.annotations.PersistentBuildItem;

@PersistentBuildItem
public class JavaSourceDirectoryBuildItem extends SimpleBuildItem {

    private final Path directory;

    public JavaSourceDirectoryBuildItem(Path directory) {
        this.directory = directory;
    }

    public Path getDirectory() {
        return directory;
    }
}
