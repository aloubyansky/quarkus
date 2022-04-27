package io.quarkus.registry.client.maven;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.RegistryResolutionException;
import io.quarkus.registry.catalog.ExtensionCatalog.Mutable;
import io.quarkus.registry.util.PlatformArtifacts;
import org.eclipse.aether.artifact.DefaultArtifact;

public class MavenPlatformExtensionsWithOverridesResolver extends MavenPlatformExtensionsResolver {

    private final MavenRegistryArtifactResolver overridesResolver;

    public MavenPlatformExtensionsWithOverridesResolver(MavenRegistryArtifactResolver catalogResolver,
            MavenRegistryArtifactResolver overridesResolver,
            MessageWriter log) {
        super(catalogResolver, log);
        this.overridesResolver = overridesResolver;
    }

    @Override
    public Mutable resolvePlatformExtensions(ArtifactCoords platformCoords) throws RegistryResolutionException {
        Mutable catalog = super.resolvePlatformExtensions(platformCoords);
        final ArtifactCoords catalogCoords = PlatformArtifacts.getCatalogArtifactForBom(catalog.getBom());

        try {
            overridesResolver.resolve(new DefaultArtifact(catalogCoords.getGroupId(), catalogCoords.getArtifactId(),
                    catalogCoords.getClassifier(), catalogCoords.getType(), "1.0-SNAPSHOT"));
        } catch (BootstrapMavenException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return catalog;
    }
}
