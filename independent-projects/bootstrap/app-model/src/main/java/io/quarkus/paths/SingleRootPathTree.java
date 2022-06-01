package io.quarkus.paths;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public interface SingleRootPathTree extends PathTree {

    Path getRoot();

    @Override
    default Collection<Path> getRoots() {
        return List.of(getRoot());
    }
}
