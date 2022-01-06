package io.quarkus.paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Manifest;

public interface PathTree {

    static PathTree ofDirectoryOrFile(Path p) {
        try {
            final BasicFileAttributes fileAttributes = Files.readAttributes(p, BasicFileAttributes.class);
            return fileAttributes.isDirectory() ? new DirectoryPathTree(p) : new FilePathTree(p);
        } catch (IOException e) {
            throw new IllegalArgumentException(p + " does not exist", e);
        }
    }

    static PathTree ofDirectoryOrArchive(Path p) {
        try {
            final BasicFileAttributes fileAttributes = Files.readAttributes(p, BasicFileAttributes.class);
            return fileAttributes.isDirectory() ? new DirectoryPathTree(p) : new ArchivePathTree(p);
        } catch (IOException e) {
            throw new IllegalArgumentException(p + " does not exist", e);
        }
    }

    static PathTree ofArchive(Path archive) {
        if (!Files.exists(archive)) {
            throw new IllegalArgumentException(archive + " does not exist");
        }
        return new ArchivePathTree(archive);
    }

    static PathTreeBuilder builder() {
        return new PathTreeBuilder(false);
    }

    static PathTreeBuilder archiveBuilder() {
        return new PathTreeBuilder(true);
    }

    Collection<Path> getRoots();

    default boolean isEmpty() {
        return getRoots().isEmpty();
    }

    Manifest getManifest();

    void walk(PathVisitor visitor);

    default <T> T apply(String relativePath, Function<PathVisit, T> func) {
        return apply(relativePath, func, true);
    }

    <T> T apply(String relativePath, Function<PathVisit, T> func, boolean multiReleaseSupport);

    default void accept(String relativePath, Consumer<PathVisit> consumer) {
        accept(relativePath, consumer, true);
    }

    void accept(String relativePath, Consumer<PathVisit> consumer, boolean multiReleaseSupport);

    default boolean contains(String relativePath) {
        return contains(relativePath, true);
    }

    boolean contains(String relativePath, boolean multiReleaseSupport);

    OpenPathTree open();
}
