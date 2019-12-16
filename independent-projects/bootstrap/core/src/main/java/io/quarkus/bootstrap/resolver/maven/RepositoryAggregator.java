package io.quarkus.bootstrap.resolver.maven;

import java.util.List;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

public interface RepositoryAggregator {

    List<RemoteRepository> aggregateRepositories(RepositorySystemSession session,
            List<RemoteRepository> dominantRepositories,
            List<RemoteRepository> recessiveRepositories, boolean recessiveIsRaw);
}
