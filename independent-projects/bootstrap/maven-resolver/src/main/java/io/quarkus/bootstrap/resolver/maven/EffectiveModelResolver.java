package io.quarkus.bootstrap.resolver.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.apache.maven.model.Model;
import org.eclipse.aether.repository.RemoteRepository;

import io.quarkus.maven.dependency.ArtifactCoords;

public interface EffectiveModelResolver {

    static EffectiveModelResolver getOfflineModelResolver(Path localRepo) {
        Objects.requireNonNull(localRepo, "Local repo path is null");
        if (!Files.exists(localRepo)) {
            throw new IllegalArgumentException(localRepo + " does not exist");
        }
        return new LocalRepositoryEffectiveModelResolver(localRepo.toFile());
    }

    static EffectiveModelResolver of(MavenArtifactResolver resolver) {
        return new DefaultEffectiveModelResolver(resolver);
    }

    default Model resolveEffectiveModel(ArtifactCoords coords) {
        return resolveEffectiveModel(coords, List.of());
    }

    Model resolveEffectiveModel(ArtifactCoords coords, List<RemoteRepository> repos);
}
