package io.quarkus.registry.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.Constants;
import io.quarkus.registry.ExtensionCatalogResolver;
import io.quarkus.registry.config.RegistriesConfig;
import io.quarkus.registry.config.RegistriesConfigMapperHelper;
import io.quarkus.registry.config.RegistryConfig;
import io.quarkus.registry.config.RegistryDescriptorConfig;
import io.quarkus.registry.config.RegistryMavenConfig;
import io.quarkus.registry.config.RegistryMavenRepoConfig;
import io.quarkus.registry.config.RegistryNonPlatformExtensionsConfig;
import io.quarkus.registry.config.RegistryPlatformsConfig;

/**
 * Test that uses MavenRegistryClientFactory (the production code path) instead of
 * TestRegistryClient, to reproduce a user-reported issue where a custom on-premise
 * registry's extensions cannot be found when using the CLI.
 *
 * The setup simulates:
 * - A custom registry with platform "com.acme.quarkus" having stream "3.23"
 * - An upstream registry with platform "io.quarkus.platform" also having stream "3.23"
 * - The custom platform has NO quarkus-bom member, only its own BOM with custom extensions
 */
public class CustomRegistryWithMavenResolverTest {

    private static final String CUSTOM_REGISTRY_ID = "registry.acme.com";
    private static final String UPSTREAM_REGISTRY_ID = "registry.upstream.test";

    private static final String CUSTOM_REGISTRY_GROUP_ID = "com.acme.registry";
    private static final String UPSTREAM_REGISTRY_GROUP_ID = "test.upstream.registry";

    private static final String CUSTOM_PLATFORM_KEY = "com.acme.quarkus";
    private static final String UPSTREAM_PLATFORM_KEY = "io.quarkus.platform";

    private static Path registryWorkDir;
    private static Path registryRepoDir;
    private static MavenArtifactResolver mvn;

    @BeforeAll
    static void setup() throws Exception {
        registryWorkDir = Paths.get("target").resolve("test-maven-registry").normalize().toAbsolutePath();
        registryRepoDir = registryWorkDir.resolve("repo");
        Files.createDirectories(registryRepoDir);

        mvn = MavenArtifactResolver.builder()
                .setWorkspaceDiscovery(false)
                .setLocalRepository(registryRepoDir.toString())
                .build();

        setupCustomRegistry();
        setupUpstreamRegistry();
    }

    private static void setupCustomRegistry() throws Exception {
        // 1. Registry descriptor
        RegistryConfig.Mutable descriptor = RegistryConfig.builder();
        descriptor.setId(CUSTOM_REGISTRY_ID);
        descriptor.setDescriptor(RegistryDescriptorConfig.builder()
                .setArtifact(descriptorCoords(CUSTOM_REGISTRY_GROUP_ID))
                .build());
        descriptor.setPlatforms(RegistryPlatformsConfig.builder()
                .setArtifact(platformsCatalogCoords(CUSTOM_REGISTRY_GROUP_ID))
                .setExtensionCatalogsIncluded(true)
                .build());
        descriptor.setNonPlatformExtensions(RegistryNonPlatformExtensionsConfig.builder()
                .setDisabled(true)
                .build());

        installJsonArtifact(descriptor, descriptorCoords(CUSTOM_REGISTRY_GROUP_ID));

        // 2. Default platform catalog (recommended streams)
        PlatformCatalog customPlatforms = PlatformCatalog.builder()
                .addPlatform(Platform.builder()
                        .setPlatformKey(CUSTOM_PLATFORM_KEY)
                        .addStream(PlatformStream.builder()
                                .setId("3.23")
                                .addRelease(PlatformRelease.builder()
                                        .setVersion(PlatformReleaseVersion.fromString("3.23.0+3331"))
                                        .setQuarkusCoreVersion("3.33.1")
                                        .setMemberBoms(List.of(
                                                ArtifactCoords.pom(CUSTOM_PLATFORM_KEY, "acme-bom", "3.23.0+3331")))
                                        .build())
                                .build())
                        .addStream(PlatformStream.builder()
                                .setId("3.17")
                                .addRelease(PlatformRelease.builder()
                                        .setVersion(PlatformReleaseVersion.fromString("3.17.2+3272"))
                                        .setQuarkusCoreVersion("3.27.2")
                                        .setMemberBoms(List.of(
                                                ArtifactCoords.pom(CUSTOM_PLATFORM_KEY, "acme-bom", "3.17.2+3272")))
                                        .build())
                                .build())
                        .build())
                .build();

        installJsonArtifact(customPlatforms, platformsCatalogCoords(CUSTOM_REGISTRY_GROUP_ID));

        // 3. Member extension catalog for acme-bom 3.23.0+3331
        String acmeBomVersion = "3.23.0+3331";
        ExtensionCatalog acmeCatalog = ExtensionCatalog.builder()
                .setId(catalogArtifactCoords(CUSTOM_PLATFORM_KEY, "acme-bom", acmeBomVersion).toString())
                .setBom(ArtifactCoords.pom(CUSTOM_PLATFORM_KEY, "acme-bom", acmeBomVersion))
                .setPlatform(true)
                .setQuarkusCoreVersion("3.33.1")
                .addExtension(Extension.builder()
                        .setArtifact(ArtifactCoords.jar("com.acme", "acme-stdlib", acmeBomVersion))
                        .setName("acme-stdlib")
                        .build())
                .addExtension(Extension.builder()
                        .setArtifact(ArtifactCoords.jar("com.acme", "acme-config-yaml", acmeBomVersion))
                        .setName("acme-config-yaml")
                        .build())
                .build();

        installJsonArtifact(acmeCatalog, catalogArtifactCoords(CUSTOM_PLATFORM_KEY, "acme-bom", acmeBomVersion));

        // 4. Member extension catalog for acme-bom 3.17.2+3272
        String acmeBomVersion2 = "3.17.2+3272";
        ExtensionCatalog acmeCatalog2 = ExtensionCatalog.builder()
                .setId(catalogArtifactCoords(CUSTOM_PLATFORM_KEY, "acme-bom", acmeBomVersion2).toString())
                .setBom(ArtifactCoords.pom(CUSTOM_PLATFORM_KEY, "acme-bom", acmeBomVersion2))
                .setPlatform(true)
                .setQuarkusCoreVersion("3.27.2")
                .addExtension(Extension.builder()
                        .setArtifact(ArtifactCoords.jar("com.acme", "acme-stdlib", acmeBomVersion2))
                        .setName("acme-stdlib")
                        .build())
                .build();

        installJsonArtifact(acmeCatalog2, catalogArtifactCoords(CUSTOM_PLATFORM_KEY, "acme-bom", acmeBomVersion2));
    }

    private static void setupUpstreamRegistry() throws Exception {
        // 1. Registry descriptor
        RegistryConfig.Mutable descriptor = RegistryConfig.builder();
        descriptor.setId(UPSTREAM_REGISTRY_ID);
        descriptor.setDescriptor(RegistryDescriptorConfig.builder()
                .setArtifact(descriptorCoords(UPSTREAM_REGISTRY_GROUP_ID))
                .build());
        descriptor.setPlatforms(RegistryPlatformsConfig.builder()
                .setArtifact(platformsCatalogCoords(UPSTREAM_REGISTRY_GROUP_ID))
                .setExtensionCatalogsIncluded(true)
                .build());
        descriptor.setNonPlatformExtensions(RegistryNonPlatformExtensionsConfig.builder()
                .setDisabled(true)
                .build());

        installJsonArtifact(descriptor, descriptorCoords(UPSTREAM_REGISTRY_GROUP_ID));

        // 2. Default platform catalog
        PlatformCatalog upstreamPlatforms = PlatformCatalog.builder()
                .addPlatform(Platform.builder()
                        .setPlatformKey(UPSTREAM_PLATFORM_KEY)
                        .addStream(PlatformStream.builder()
                                .setId("3.33")
                                .addRelease(PlatformRelease.builder()
                                        .setVersion(PlatformReleaseVersion.fromString("3.33.1"))
                                        .setQuarkusCoreVersion("3.33.1")
                                        .setMemberBoms(List.of(
                                                ArtifactCoords.pom(UPSTREAM_PLATFORM_KEY, "quarkus-bom", "3.33.1")))
                                        .build())
                                .build())
                        .addStream(PlatformStream.builder()
                                .setId("3.27")
                                .addRelease(PlatformRelease.builder()
                                        .setVersion(PlatformReleaseVersion.fromString("3.27.2"))
                                        .setQuarkusCoreVersion("3.27.2")
                                        .setMemberBoms(List.of(
                                                ArtifactCoords.pom(UPSTREAM_PLATFORM_KEY, "quarkus-bom", "3.27.2")))
                                        .build())
                                .build())
                        .addStream(PlatformStream.builder()
                                .setId("3.23")
                                .addRelease(PlatformRelease.builder()
                                        .setVersion(PlatformReleaseVersion.fromString("3.23.0"))
                                        .setQuarkusCoreVersion("3.23.0")
                                        .setMemberBoms(List.of(
                                                ArtifactCoords.pom(UPSTREAM_PLATFORM_KEY, "quarkus-bom", "3.23.0")))
                                        .build())
                                .build())
                        .build())
                .build();

        installJsonArtifact(upstreamPlatforms, platformsCatalogCoords(UPSTREAM_REGISTRY_GROUP_ID));

        // 3. Member extension catalogs for upstream
        for (String version : List.of("3.33.1", "3.27.2", "3.23.0")) {
            ExtensionCatalog upstreamCatalog = ExtensionCatalog.builder()
                    .setId(catalogArtifactCoords(UPSTREAM_PLATFORM_KEY, "quarkus-bom", version).toString())
                    .setBom(ArtifactCoords.pom(UPSTREAM_PLATFORM_KEY, "quarkus-bom", version))
                    .setPlatform(true)
                    .setQuarkusCoreVersion(version)
                    .addExtension(Extension.builder()
                            .setArtifact(ArtifactCoords.jar("io.quarkus", "quarkus-core", version))
                            .setName("quarkus-core")
                            .build())
                    .addExtension(Extension.builder()
                            .setArtifact(ArtifactCoords.jar("io.quarkus", "quarkus-rest", version))
                            .setName("quarkus-rest")
                            .build())
                    .build();

            installJsonArtifact(upstreamCatalog,
                    catalogArtifactCoords(UPSTREAM_PLATFORM_KEY, "quarkus-bom", version));
        }
    }

    @Test
    void resolveExtensionCatalogWithStreamShouldIncludeCustomExtensions() throws Exception {
        ExtensionCatalogResolver resolver = newCatalogResolver();

        ExtensionCatalog catalog = resolver.resolveExtensionCatalog(
                PlatformStreamCoords.fromString("3.23"));

        List<String> artifactIds = catalog.getExtensions().stream()
                .map(Extension::getArtifact)
                .map(ArtifactCoords::getArtifactId)
                .toList();

        assertThat(artifactIds)
                .as("Custom extensions should be found when resolving with -S 3.23")
                .contains("acme-stdlib", "acme-config-yaml");
    }

    @Test
    void resolveDefaultExtensionCatalogShouldIncludeCustomExtensions() throws Exception {
        ExtensionCatalogResolver resolver = newCatalogResolver();

        ExtensionCatalog catalog = resolver.resolveExtensionCatalog();

        List<String> artifactIds = catalog.getExtensions().stream()
                .map(Extension::getArtifact)
                .map(ArtifactCoords::getArtifactId)
                .toList();

        assertThat(artifactIds)
                .as("Custom extensions should appear in default catalog (quarkus ext list)")
                .contains("acme-stdlib", "acme-config-yaml");
    }

    @Test
    void resolveWithQualifiedStreamShouldIncludeCustomExtensions() throws Exception {
        ExtensionCatalogResolver resolver = newCatalogResolver();

        ExtensionCatalog catalog = resolver.resolveExtensionCatalog(
                PlatformStreamCoords.fromString(CUSTOM_PLATFORM_KEY + ":3.23"));

        List<String> artifactIds = catalog.getExtensions().stream()
                .map(Extension::getArtifact)
                .map(ArtifactCoords::getArtifactId)
                .toList();

        assertThat(artifactIds)
                .as("Custom extensions should be found with qualified stream")
                .contains("acme-stdlib", "acme-config-yaml");
    }

    @Test
    void resolvePlatformCatalogShouldIncludeBothPlatforms() throws Exception {
        ExtensionCatalogResolver resolver = newCatalogResolver();

        PlatformCatalog catalog = resolver.resolvePlatformCatalog();

        List<String> platformKeys = catalog.getPlatforms().stream()
                .map(Platform::getPlatformKey)
                .toList();

        assertThat(platformKeys)
                .as("Both custom and upstream platforms should be present")
                .contains(CUSTOM_PLATFORM_KEY, UPSTREAM_PLATFORM_KEY);
    }

    private ExtensionCatalogResolver newCatalogResolver() throws Exception {
        RegistriesConfig.Mutable config = RegistriesConfig.builder();

        // Custom registry (first — higher priority)
        config.addRegistry(RegistryConfig.builder()
                .setId(CUSTOM_REGISTRY_ID)
                .setDescriptor(RegistryDescriptorConfig.builder()
                        .setArtifact(descriptorCoords(CUSTOM_REGISTRY_GROUP_ID))
                        .build())
                .setPlatforms(RegistryPlatformsConfig.builder()
                        .setArtifact(platformsCatalogCoords(CUSTOM_REGISTRY_GROUP_ID))
                        .setExtensionCatalogsIncluded(true)
                        .build())
                .setNonPlatformExtensions(RegistryNonPlatformExtensionsConfig.builder()
                        .setDisabled(true)
                        .build())
                .setMaven(RegistryMavenConfig.builder()
                        .setRepository(RegistryMavenRepoConfig.builder()
                                .setId(CUSTOM_REGISTRY_ID)
                                .setUrl(registryRepoDir.toUri().toURL().toExternalForm())
                                .build())
                        .build())
                .build());

        // Upstream registry (second)
        config.addRegistry(RegistryConfig.builder()
                .setId(UPSTREAM_REGISTRY_ID)
                .setDescriptor(RegistryDescriptorConfig.builder()
                        .setArtifact(descriptorCoords(UPSTREAM_REGISTRY_GROUP_ID))
                        .build())
                .setPlatforms(RegistryPlatformsConfig.builder()
                        .setArtifact(platformsCatalogCoords(UPSTREAM_REGISTRY_GROUP_ID))
                        .setExtensionCatalogsIncluded(true)
                        .build())
                .setNonPlatformExtensions(RegistryNonPlatformExtensionsConfig.builder()
                        .setDisabled(true)
                        .build())
                .setMaven(RegistryMavenConfig.builder()
                        .setRepository(RegistryMavenRepoConfig.builder()
                                .setId(UPSTREAM_REGISTRY_ID)
                                .setUrl(registryRepoDir.toUri().toURL().toExternalForm())
                                .build())
                        .build())
                .build());

        return ExtensionCatalogResolver.builder()
                .config(config.build())
                .artifactResolver(mvn)
                .build();
    }

    // ---- Artifact coordinate helpers ----

    private static ArtifactCoords descriptorCoords(String registryGroupId) {
        return ArtifactCoords.of(registryGroupId,
                Constants.DEFAULT_REGISTRY_DESCRIPTOR_ARTIFACT_ID, null, "json",
                Constants.DEFAULT_REGISTRY_ARTIFACT_VERSION);
    }

    private static ArtifactCoords platformsCatalogCoords(String registryGroupId) {
        return ArtifactCoords.of(registryGroupId,
                Constants.DEFAULT_REGISTRY_PLATFORMS_CATALOG_ARTIFACT_ID, null, "json",
                Constants.DEFAULT_REGISTRY_ARTIFACT_VERSION);
    }

    private static ArtifactCoords catalogArtifactCoords(String groupId, String artifactId, String version) {
        return ArtifactCoords.of(groupId,
                artifactId + Constants.PLATFORM_DESCRIPTOR_ARTIFACT_ID_SUFFIX,
                version, "json", version);
    }

    // ---- Installation helpers ----

    private static <T> void installJsonArtifact(Object content, ArtifactCoords coords) throws Exception {
        Path jsonFile = registryWorkDir.resolve("tmp")
                .resolve(coords.getGroupId().replace('.', '/'))
                .resolve(coords.getArtifactId())
                .resolve(coords.getVersion())
                .resolve(coords.getArtifactId() + "-" + coords.getVersion() + ".json");
        Files.createDirectories(jsonFile.getParent());

        if (content instanceof RegistryConfig registryConfig) {
            RegistriesConfigMapperHelper.serialize(registryConfig, jsonFile);
        } else if (content instanceof PlatformCatalog platformCatalog) {
            platformCatalog.persist(jsonFile);
        } else if (content instanceof ExtensionCatalog extensionCatalog) {
            extensionCatalog.persist(jsonFile);
        } else {
            throw new IllegalArgumentException("Unsupported content type: " + content.getClass());
        }

        Artifact artifact = new DefaultArtifact(
                coords.getGroupId(), coords.getArtifactId(),
                coords.getClassifier(), coords.getType(), coords.getVersion());
        artifact = artifact.setFile(jsonFile.toFile());
        mvn.install(artifact);
    }
}
