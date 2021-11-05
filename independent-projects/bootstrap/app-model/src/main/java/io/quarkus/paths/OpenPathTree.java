package io.quarkus.paths;

import java.io.Closeable;
import java.nio.file.Path;

public interface OpenPathTree extends PathTree, Closeable {

    PathTree getOriginalTree();

    boolean isOpen();

    default Path getPath(String relativePath) {
        return getPath(relativePath, true);
    }

    Path getPath(String relativePath, boolean multiReleaseSupport);
}
