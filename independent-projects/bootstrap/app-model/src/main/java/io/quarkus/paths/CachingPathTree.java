package io.quarkus.paths;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.jar.Manifest;

public class CachingPathTree implements OpenPathTree {

    public static CachingPathTree of(PathTree pathTree) {
        return new CachingPathTree(pathTree);
    }

    private final PathTree delegate;
    private volatile LinkedHashMap<String, PathVisitSnapshot> walkSnapshot;

    private CachingPathTree(PathTree delegate) {
        this.delegate = delegate;
    }

    @Override
    public Collection<Path> getRoots() {
        return delegate.getRoots();
    }

    @Override
    public Manifest getManifest() {
        return delegate.getManifest();
    }

    @Override
    public void walk(PathVisitor visitor) {
        final LinkedHashMap<String, PathVisitSnapshot> snapshot = walkSnapshot;
        if (snapshot != null) {
            final PathVisitWrapper wrapper = new PathVisitWrapper();
            for (PathVisitSnapshot visit : snapshot.values()) {
                wrapper.target = visit;
                visitor.visitPath(wrapper);
                if (wrapper.stopWalking) {
                    break;
                }
            }
            return;
        }

        final LinkedHashMap<String, PathVisitSnapshot> walkSnapshot = new LinkedHashMap<>();
        final PathVisitWrapper wrapper = new PathVisitWrapper();
        delegate.walk(new PathVisitor() {
            @Override
            public void visitPath(PathVisit visit) {
                final PathVisitSnapshot snapshot = new PathVisitSnapshot(visit);
                walkSnapshot.put(snapshot.getRelativePath("/"), snapshot);
                if (wrapper.stopWalking) {
                    return;
                }
                wrapper.target = snapshot;
                visitor.visitPath(wrapper);
            }
        });
        if (this.walkSnapshot == null) {
            this.walkSnapshot = walkSnapshot;
        }
    }

    @Override
    public <T> T processPath(String relativePath, Function<PathVisit, T> func, boolean multiReleaseSupport) {
        final LinkedHashMap<String, PathVisitSnapshot> snapshot = walkSnapshot;
        if (snapshot != null) {
            return func.apply((PathVisit) snapshot.get(relativePath));
        }
        return delegate.processPath(relativePath, func, multiReleaseSupport);
    }

    @Override
    public OpenPathTree openTree() {
        return this;
    }

    @Override
    public void close() throws IOException {
        walkSnapshot = null;
        if (delegate instanceof OpenPathTree) {
            ((OpenPathTree) delegate).close();
        }
    }

    @Override
    public PathTree getOriginalTree() {
        return delegate instanceof OpenPathTree ? ((OpenPathTree) delegate).getOriginalTree() : delegate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        CachingPathTree other = (CachingPathTree) obj;
        return Objects.equals(delegate, other.delegate);
    }

    private static class PathVisitWrapper implements PathVisit {
        PathVisit target;
        boolean stopWalking;

        @Override
        public Path getRoot() {
            return target.getRoot();
        }

        @Override
        public Path getPath() {
            return target.getPath();
        }

        @Override
        public Path getRelativePath() {
            return target.getRelativePath();
        }

        @Override
        public URL getUrl() {
            return target.getUrl();
        }

        @Override
        public void stopWalking() {
            stopWalking = true;
        }
    }

    private static class PathVisitSnapshot implements PathVisit {

        private final Path root;
        private final Path path;
        private final Path relativePath;
        private volatile URL url;

        private PathVisitSnapshot(PathVisit visit) {
            this.root = visit.getRoot();
            this.path = visit.getPath();
            this.relativePath = visit.getRelativePath();
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Path getPath() {
            return path;
        }

        @Override
        public URL getUrl() {
            if (url != null) {
                return url;
            }
            try {
                return url = path.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException("Failed to translate " + path.toUri() + " to a URL", e);
            }
        }

        @Override
        public Path getRelativePath() {
            return relativePath;
        }

        @Override
        public void stopWalking() {
        }
    }
}
