package io.quarkus.maven.dependency;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.Mappable;
import io.quarkus.bootstrap.model.MappableCollectionFactory;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.paths.EmptyPathTree;
import io.quarkus.paths.FilteredPathTree;
import io.quarkus.paths.MultiRootPathTree;
import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathFilter;
import io.quarkus.paths.PathTree;

public interface ResolvedDependency extends Dependency {

    static void putInMap(ResolvedDependency dependency, Map<String, Object> map, MappableCollectionFactory factory) {
        ArtifactDependency.putInMap(dependency, map, factory);
        map.put(BootstrapConstants.MAPPABLE_RESOLVED_PATHS,
                Mappable.iterableToStringCollection(dependency.getResolvedPaths(), factory));

        final Collection<ArtifactCoords> deps = dependency.getDependencies();
        if (!deps.isEmpty()) {
            map.put(BootstrapConstants.MAPPABLE_DEPENDENCIES,
                    Mappable.toStringCollection(deps, ArtifactCoords::toGACTVString, factory));
        }

        if (dependency.getWorkspaceModule() != null) {
            map.put(BootstrapConstants.MAPPABLE_MODULE, dependency.getWorkspaceModule().asMap(factory));
        }
    }

    PathCollection getResolvedPaths();

    Collection<ArtifactCoords> getDependencies();

    default boolean isResolved() {
        final PathCollection paths = getResolvedPaths();
        return paths != null && !paths.isEmpty();
    }

    default WorkspaceModule getWorkspaceModule() {
        return null;
    }

    default ArtifactSources getSources() {
        final WorkspaceModule module = getWorkspaceModule();
        return module == null ? null : module.getSources(getClassifier());
    }

    default PathTree getContentTree() {
        return getContentTree(null);
    }

    default PathTree getContentTree(PathFilter pathFilter) {
        final WorkspaceModule module = getWorkspaceModule();
        final PathTree workspaceTree = module == null ? EmptyPathTree.getInstance() : module.getContentTree(getClassifier());
        if (!workspaceTree.isEmpty()) {
            return pathFilter == null ? workspaceTree : new FilteredPathTree(workspaceTree, pathFilter);
        }
        final PathCollection paths = getResolvedPaths();
        if (paths == null || paths.isEmpty()) {
            return EmptyPathTree.getInstance();
        }
        if (paths.isSinglePath()) {
            final Path p = paths.getSinglePath();
            return isJar() ? PathTree.ofDirectoryOrArchive(p, pathFilter) : PathTree.ofDirectoryOrFile(p, pathFilter);
        }
        final PathTree[] trees = new PathTree[paths.size()];
        int i = 0;
        for (Path p : paths) {
            trees[i++] = PathTree.ofDirectoryOrArchive(p, pathFilter);
        }
        return new MultiRootPathTree(trees);
    }
}
