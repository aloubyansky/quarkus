package io.quarkus.bootstrap.workspace;

import io.quarkus.paths.EmptyPathTree;
import io.quarkus.paths.MultiRootPathTree;
import io.quarkus.paths.PathTree;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public interface ArtifactSources {

    String MAIN = "";
    String TEST = "tests";

    static ArtifactSources main(SourceDir sources, SourceDir resources) {
        return new DefaultArtifactSources(MAIN, List.of(sources), List.of(resources));
    }

    static ArtifactSources test(SourceDir sources, SourceDir resources) {
        return new DefaultArtifactSources(TEST, List.of(sources), List.of(resources));
    }

    String getClassifier();

    Collection<SourceDir> getSourceDirs();

    Collection<SourceDir> getResourceDirs();

    default boolean isOutputAvailable() {
        for (SourceDir src : getSourceDirs()) {
            if (src.isOutputAvailable()) {
                return true;
            }
        }
        for (SourceDir src : getResourceDirs()) {
            if (src.isOutputAvailable()) {
                return true;
            }
        }
        return false;
    }

    default PathTree getOutputTree() {
        final Collection<SourceDir> sourceDirs = getSourceDirs();
        final Collection<SourceDir> resourceDirs = getResourceDirs();

        final Iterator<SourceDir> i;
        if (sourceDirs.isEmpty()) {
            i = resourceDirs.iterator();
        } else if (resourceDirs.isEmpty()) {
            i = sourceDirs.iterator();
        } else {
            i = new Iterator<SourceDir>() {
                Iterator<SourceDir> i = sourceDirs.iterator();
                int c = 0;

                @Override
                public boolean hasNext() {
                    if (c < sourceDirs.size() + resourceDirs.size()) {
                        return true;
                    }
                    if (i.hasNext()) {
                        return true;
                    }
                    return false;
                }

                @Override
                public SourceDir next() {
                    if (c++ == sourceDirs.size()) {
                        i = resourceDirs.iterator();
                    }
                    return i.next();
                }
            };
        }

        PathTree firstTree = null;
        List<PathTree> trees = null;
        while (i.hasNext()) {
            final SourceDir src = i.next();
            if (!src.isOutputAvailable()) {
                continue;
            }
            final PathTree outputTree = src.getOutputTree();
            if (trees != null) {
                if (!trees.contains(outputTree)) {
                    trees.add(outputTree);
                }
            } else if (firstTree == null) {
                firstTree = outputTree;
            } else if (!firstTree.equals(outputTree)) {
                trees = new ArrayList<>(sourceDirs.size() + resourceDirs.size());
                trees.add(firstTree);
                trees.add(outputTree);
            }
        }

        if (firstTree == null) {
            return EmptyPathTree.getInstance();
        }
        return trees == null ? firstTree : new MultiRootPathTree(trees.toArray(new PathTree[0]));
    }
}
