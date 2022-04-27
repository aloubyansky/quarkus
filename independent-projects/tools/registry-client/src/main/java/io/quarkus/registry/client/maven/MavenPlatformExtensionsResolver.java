package io.quarkus.registry.client.maven;

import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.RegistryResolutionException;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.registry.client.RegistryPlatformExtensionsResolver;
import io.quarkus.registry.util.PlatformArtifacts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.transfer.ArtifactNotFoundException;

public class MavenPlatformExtensionsResolver implements RegistryPlatformExtensionsResolver {

    private final MavenRegistryArtifactResolver artifactResolver;
    protected final MessageWriter log;

    public MavenPlatformExtensionsResolver(MavenRegistryArtifactResolver artifactResolver,
            MessageWriter log) {
        this.artifactResolver = Objects.requireNonNull(artifactResolver);
        this.log = Objects.requireNonNull(log);
    }

    @Override
    public ExtensionCatalog.Mutable resolvePlatformExtensions(ArtifactCoords platformCoords)
            throws RegistryResolutionException {
        if (platformCoords.getVersion() == null) {
            platformCoords = ArtifactCoords.of(platformCoords.getGroupId(), platformCoords.getArtifactId(),
                    platformCoords.getClassifier(), platformCoords.getType(),
                    resolveLatestBomVersion(platformCoords, "[0-alpha,)"));
        } else if (isVersionRange(platformCoords.getVersion())) {
            platformCoords = ArtifactCoords.of(platformCoords.getGroupId(), platformCoords.getArtifactId(),
                    platformCoords.getClassifier(), platformCoords.getType(),
                    resolveLatestBomVersion(platformCoords, platformCoords.getVersion()));
        }
        final Path jsonPath = resolveCatalog(PlatformArtifacts.ensureCatalogArtifact(platformCoords));
        try {
            return ExtensionCatalog.mutableFromFile(jsonPath);
        } catch (IOException e) {
            throw new RegistryResolutionException("Failed to parse Quarkus extension catalog " + jsonPath, e);
        }
    }

    protected Path resolveCatalog(ArtifactCoords catalogCoords) throws RegistryResolutionException {
        return resolveCatalogArtifact(catalogCoords,
                new DefaultArtifact(catalogCoords.getGroupId(), catalogCoords.getArtifactId(),
                        catalogCoords.getClassifier(), catalogCoords.getType(), catalogCoords.getVersion()),
                artifactResolver);
    }

    protected Path resolveCatalogArtifact(ArtifactCoords catalogCoords, Artifact catalogArtifact,
            MavenRegistryArtifactResolver artifactResolver)
            throws RegistryResolutionException {
        log.debug("Resolving platform extension catalog %s", catalogArtifact);
        try {
            return artifactResolver.resolve(catalogArtifact);
        } catch (Exception e) {
            RemoteRepository repo = null;
            Throwable t = e;
            while (t != null) {
                if (t instanceof ArtifactNotFoundException) {
                    repo = ((ArtifactNotFoundException) t).getRepository();
                    break;
                }
                t = t.getCause();
            }
            final StringBuilder buf = new StringBuilder();
            buf.append("Failed to resolve extension catalog of ")
                    .append(PlatformArtifacts.ensureBomArtifact(catalogCoords).toCompactCoords());
            if (repo != null) {
                buf.append(" from Maven repository ").append(repo.getId()).append(" (").append(repo.getUrl()).append(")");
                final List<RemoteRepository> mirrored = repo.getMirroredRepositories();
                if (!mirrored.isEmpty()) {
                    buf.append(" which is a mirror of ");
                    buf.append(mirrored.get(0).getId()).append(" (").append(mirrored.get(0).getUrl()).append(")");
                    for (int i = 1; i < mirrored.size(); ++i) {
                        buf.append(", ").append(mirrored.get(i).getId()).append(" (").append(mirrored.get(i).getUrl())
                                .append(")");
                    }
                    buf.append(". The mirror may be out of sync.");
                }
            }
            throw new RegistryResolutionException(buf.toString(), e);
        }
    }

    private String resolveLatestBomVersion(ArtifactCoords bom, String versionRange)
            throws RegistryResolutionException {
        final Artifact bomArtifact = new DefaultArtifact(bom.getGroupId(),
                PlatformArtifacts.ensureBomArtifactId(bom.getArtifactId()),
                "", ArtifactCoords.TYPE_POM, bom.getVersion());
        log.debug("Resolving the latest version of %s:%s:%s:%s in the range %s", bom.getGroupId(), bom.getArtifactId(),
                bom.getClassifier(), bom.getType(), versionRange);
        try {
            return artifactResolver.getLatestVersionFromRange(bomArtifact, versionRange);
        } catch (Exception e) {
            throw new RegistryResolutionException("Failed to resolve the latest version of " + bomArtifact.getGroupId()
                    + ":" + bom.getArtifactId() + ":" + bom.getClassifier() + ":" + bom.getType() + ":" + versionRange, e);
        }
    }

    private static boolean isVersionRange(String versionStr) {
        if (versionStr == null || versionStr.isEmpty()) {
            return false;
        }
        char c = versionStr.charAt(0);
        if (c == '[' || c == '(') {
            return true;
        }
        c = versionStr.charAt(versionStr.length() - 1);
        if (c == ']' || c == ')') {
            return true;
        }
        return versionStr.indexOf(',') >= 0;
    }
}
