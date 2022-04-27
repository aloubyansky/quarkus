package io.quarkus.registry.client.maven;

import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.RegistryResolutionException;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.aether.artifact.DefaultArtifact;

public class MavenPlatformExtensionsSnapshotResolver extends MavenPlatformExtensionsResolver {

    private final MavenRegistryArtifactResolver registryResolver;
    private final String snapshotVersion;

    public MavenPlatformExtensionsSnapshotResolver(MavenRegistryArtifactResolver registryResolver,
            MavenRegistryArtifactResolver userResolver,
            String snapshotVersion, MessageWriter log) {
        super(userResolver, log);
        this.registryResolver = Objects.requireNonNull(registryResolver);
        this.snapshotVersion = snapshotVersion;
    }

    @Override
    protected Path resolveCatalog(ArtifactCoords catalogCoords) throws RegistryResolutionException {
        final DefaultArtifact snapshotArtifact = new DefaultArtifact(catalogCoords.getGroupId(), catalogCoords.getArtifactId(),
                catalogCoords.getClassifier(), catalogCoords.getType(), snapshotVersion);
        try {
            return resolveCatalogArtifact(catalogCoords, snapshotArtifact, registryResolver);
        } catch (RegistryResolutionException e) {
            log.debug(e.getLocalizedMessage());
        }
        return super.resolveCatalog(catalogCoords);
    }

}
