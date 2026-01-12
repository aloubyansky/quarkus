package io.quarkus.bootstrap.resolver.maven;

import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.util.artifact.JavaScopes;

public class DependencyCollectingSelectorFactory implements DependencySelector {

    private final DependencySelector delegate;
    private final ApplicationDependencyMap appDepMap;

    public DependencyCollectingSelectorFactory(DependencySelector delegate, ApplicationDependencyMap appDepMap) {
        this.delegate = delegate;
        this.appDepMap = appDepMap;
    }

    @Override
    public boolean selectDependency(Dependency dependency) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
        return new DependencyCollectingSelector(delegate.deriveChildSelector(context),
                appDepMap
                        .getOrCreate(context.getDependency() == null ? new Dependency(context.getArtifact(), JavaScopes.COMPILE)
                                : context.getDependency()));
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass())
            return false;
        DependencyCollectingSelectorFactory that = (DependencyCollectingSelectorFactory) o;
        return delegate.equals(that.delegate);
    }

    @Override
    public int hashCode() {
        return 31 * delegate.hashCode();
    }
}
