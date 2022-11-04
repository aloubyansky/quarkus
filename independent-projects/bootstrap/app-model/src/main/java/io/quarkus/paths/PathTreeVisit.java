package io.quarkus.paths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

class PathTreeVisit implements PathVisit {

    static final byte WALKING_STOP = 1;
    static final byte WALKING_SKIP_CHILDREN = 2;
    static final byte WALKING_SKIP_SIBLINGS = 3;

    static void walk(Path root, Path rootDir, PathFilter pathFilter, Map<String, String> multiReleaseMapping,
            PathVisitor visitor) {
        final PathTreeVisit visit = new PathTreeVisit(root, rootDir, pathFilter, multiReleaseMapping);
        try {
            Files.walkFileTree(rootDir, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return visitPath(dir);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    return visitPath(file);
                }

                private FileVisitResult visitPath(Path path) {
                    if (!visit.setCurrent(path)) {
                        return FileVisitResult.CONTINUE;
                    }
                    visitor.visitPath(visit);
                    if (visit.isStopWalking()) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk directory " + root, e);
        }
        visit.visitMultiReleasePaths(visitor);
    }

    static <T> T process(Path root, Path rootDir, Path path, PathFilter pathFilter, Function<PathVisit, T> func) {
        final PathTreeVisit visit = new PathTreeVisit(root, rootDir, pathFilter, Collections.emptyMap());
        if (visit.setCurrent(path)) {
            return func.apply(visit);
        }
        return func.apply(null);
    }

    static void consume(Path root, Path rootDir, Path path, PathFilter pathFilter, Consumer<PathVisit> func) {
        final PathTreeVisit visit = new PathTreeVisit(root, rootDir, pathFilter, Collections.emptyMap());
        if (visit.setCurrent(path)) {
            func.accept(visit);
        } else {
            func.accept(null);
        }
    }

    private final Path root;
    private final Path baseDir;
    private final PathFilter pathFilter;
    private final Map<String, String> multiReleaseMapping;

    private Path current;
    private String relativePath;
    private byte walkingFlag;

    private PathTreeVisit(Path root, Path rootDir, PathFilter pathFilter, Map<String, String> multiReleaseMapping) {
        this.root = root;
        this.baseDir = rootDir;
        this.pathFilter = pathFilter;
        this.multiReleaseMapping = multiReleaseMapping == null || multiReleaseMapping.isEmpty() ? Collections.emptyMap()
                : new HashMap<>(multiReleaseMapping);
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    public Path getPath() {
        return current;
    }

    @Override
    public void stopWalking() {
        walkingFlag = WALKING_STOP;
    }

    @Override
    public void skipChildren() {
        walkingFlag = WALKING_SKIP_CHILDREN;
    }

    @Override
    public void skipSiblings() {
        walkingFlag = WALKING_SKIP_SIBLINGS;
    }

    boolean isStopWalking() {
        return walkingFlag == WALKING_STOP;
    }

    boolean isSkipChildren() {
        return walkingFlag == WALKING_SKIP_CHILDREN;
    }

    boolean isSkipSiblings() {
        return walkingFlag == WALKING_SKIP_SIBLINGS;
    }

    @Override
    public String getRelativePath(String separator) {
        if (relativePath == null) {
            return PathUtils.asString(baseDir.relativize(current), separator);
        }
        if (!current.getFileSystem().getSeparator().equals(separator)) {
            return relativePath.replace(current.getFileSystem().getSeparator(), separator);
        }
        return relativePath;
    }

    private boolean setCurrent(Path path) {
        current = path;
        relativePath = null;
        if (pathFilter != null) {
            relativePath = baseDir.relativize(path).toString();
            if (!PathFilter.isVisible(pathFilter, relativePath)) {
                return false;
            }
        }
        if (!multiReleaseMapping.isEmpty()) {
            if (relativePath == null) {
                relativePath = baseDir.relativize(path).toString();
            }
            final String mrPath = multiReleaseMapping.remove(relativePath);
            if (mrPath != null) {
                current = baseDir.resolve(mrPath);
            }
        }
        return true;
    }

    private void visitMultiReleasePaths(PathVisitor visitor) {
        for (Map.Entry<String, String> mrEntry : multiReleaseMapping.entrySet()) {
            relativePath = mrEntry.getKey();
            if (pathFilter != null && !PathFilter.isVisible(pathFilter, relativePath)) {
                continue;
            }
            current = baseDir.resolve(mrEntry.getValue());
            visitor.visitPath(this);
        }
    }
}
