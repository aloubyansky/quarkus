package io.quarkus.bootstrap.resolver.maven;

import java.nio.file.Paths;
import java.util.List;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.impl.DefaultRemoteRepositoryManager;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;

import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver.Builder;

public class BuildMavenArtifactResolverImpl implements BuildMavenArtifactResolver {

    @Override
    public MavenArtifactResolver apply(Builder builder) {

        final RepositorySystem repoSystem;
        try {
            repoSystem = builder.getRepoSystem() == null ? MavenRepoInitializer.getRepositorySystem(
                    (builder.getOffline() == null
                            ? (builder.getRepoSession() == null ? MavenRepoInitializer.getSettings().isOffline()
                                    : builder.getRepoSession().isOffline())
                            : builder.getOffline()),
                    builder.getWorkspace()) : builder.getRepoSystem();
        } catch (AppModelResolverException e) {
            throw new IllegalStateException("Failed to initialize Maven Repository system", e);
        }

        final DefaultRepositorySystemSession repoSession;
        try {
            repoSession = builder.getRepoSession() == null ? MavenRepoInitializer.newSession(repoSystem) : new DefaultRepositorySystemSession(builder.getRepoSession());
        } catch (AppModelResolverException e) {
            throw new IllegalStateException("Failed to initialize Maven Repository session", e);
        }
        if(builder.getOffline() != null && builder.getOffline().booleanValue() != repoSession.isOffline()) {
            repoSession.setOffline(builder.getOffline());
        }

        MavenLocalRepositoryManager lrm = null;
        if (builder.getRepoHome() != null) {
            if (builder.isReTryFailedResolutionsAgainstDefaultLocalRepo()) {
                try {
                    lrm = new MavenLocalRepositoryManager(
                            repoSystem.newLocalRepositoryManager(repoSession, new LocalRepository(builder.getRepoHome().toString())),
                            Paths.get(MavenRepoInitializer.getLocalRepo(MavenRepoInitializer.getSettings())));
                } catch (AppModelResolverException e) {
                    throw new IllegalStateException("Failed to resolve Maven repository settings", e);
                }
                repoSession.setLocalRepositoryManager(lrm);
            } else {
                repoSession.setLocalRepositoryManager(
                        repoSystem.newLocalRepositoryManager(repoSession, new LocalRepository(builder.getRepoHome().toString())));
            }
        }
        MavenLocalRepositoryManager localRepoManager = lrm;

        if(repoSession.getCache() == null) {
            repoSession.setCache(new DefaultRepositoryCache());
        }

        if (builder.getWorkspace() != null) {
            repoSession.setWorkspaceReader(builder.getWorkspace());
        }

        final List<RemoteRepository> remoteRepos;
        try {
            remoteRepos = builder.getRemoteRepos() == null ? MavenRepoInitializer.getRemoteRepos(repoSystem, repoSession) : builder.getRemoteRepos();
        } catch (AppModelResolverException e) {
            throw new IllegalStateException("Failed to initialize Maven remote repositories", e);
        }

        final DefaultRemoteRepositoryManager remoteRepoManager = new DefaultRemoteRepositoryManager();
        remoteRepoManager.initService(MavenRepositorySystemUtils.newServiceLocator());

        try {
            return new MavenArtifactResolver(repoSystem, repoSession, remoteRepos, localRepoManager, new RepositoryAggregator() {
                @Override
                public List<RemoteRepository> aggregateRepositories(RepositorySystemSession session,
                        List<RemoteRepository> dominantRepositories, List<RemoteRepository> recessiveRepositories,
                        boolean recessiveIsRaw) {
                    return remoteRepoManager.aggregateRepositories(repoSession, dominantRepositories, recessiveRepositories, recessiveIsRaw);
                }});
        } catch (AppModelResolverException e) {
            throw new IllegalStateException("Failed to instantiate Maven artifact resolver", e);
        }
    }
}
