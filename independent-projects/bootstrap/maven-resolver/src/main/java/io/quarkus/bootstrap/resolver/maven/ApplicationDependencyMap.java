package io.quarkus.bootstrap.resolver.maven;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.util.artifact.JavaScopes;

import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;

class ApplicationDependencyMap {
    private final Map<ArtifactCoords, ArtifactDependencyMap> deps = new ConcurrentHashMap<>();

    ArtifactDependencyMap getOrCreate(Dependency dep) {
        final Artifact a = dep.getArtifact();
        return deps.computeIfAbsent(
                ArtifactCoords.of(a.getGroupId(), a.getArtifactId(), a.getClassifier(), a.getExtension(), a.getVersion()),
                k -> new ArtifactDependencyMap(this, dep));
    }

    ArtifactDependencyMap getOrCreate(Artifact artifact) {
        return deps.computeIfAbsent(
                ArtifactCoords.of(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(),
                        artifact.getExtension(), artifact.getVersion()),
                k -> new ArtifactDependencyMap(this, new Dependency(artifact, JavaScopes.COMPILE)));
    }

    ArtifactDependencyMap get(ResolvedDependencyBuilder builder) {
        return deps.get(ArtifactCoords.of(builder.getGroupId(), builder.getArtifactId(), builder.getClassifier(),
                builder.getType(), builder.getVersion()));
    }
}
