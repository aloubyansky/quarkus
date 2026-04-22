package io.quarkus.devtools.project.create;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.devtools.project.QuarkusProjectHelper;
import io.quarkus.devtools.testing.registry.client.TestRegistryClientBuilder;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.registry.ExtensionCatalogResolver;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.registry.catalog.PlatformStreamCoords;

public class CustomRegistryWithSameStreamIdAsUpstreamWoQuarkusBomTest extends MultiplePlatformBomsTestBase {

    private static final String CUSTOM_PLATFORM_KEY = "com.acme.quarkus";
    private static final String UPSTREAM_PLATFORM_KEY = "io.quarkus.platform";

    @BeforeAll
    public static void setup() throws Exception {
        TestRegistryClientBuilder.newInstance()
                //.debug()
                .baseDir(configDir())
                // Custom on-premise registry (listed first, higher priority)
                // No recognizedQuarkusVersions configured
                .newRegistry("registry.acme.com")
                .newPlatform(CUSTOM_PLATFORM_KEY)
                // Stream "3.23" (current) — same ID as an upstream stream
                .newStream("3.23")
                .newRelease("3.23.0+3331")
                .quarkusVersion("3.33.1")
                .newMember("acme-bom")
                .addExtension("com.acme", "acme-stdlib", "3.23.0+3331")
                .addExtension("com.acme", "acme-config-yaml", "3.23.0+3331")
                .release()
                .stream().platform()
                // Stream "3.17" (older LTS)
                .newStream("3.17")
                .newRelease("3.17.2+3272")
                .quarkusVersion("3.27.2")
                .newMember("acme-bom")
                .addExtension("com.acme", "acme-stdlib", "3.17.2+3272")
                .release()
                .stream().platform()
                // Stream "3.24" (snapshot)
                .newStream("3.24")
                .newRelease("3.24.0-SNAPSHOT")
                .quarkusVersion("3.34.3")
                .newMember("acme-bom")
                .addExtension("com.acme", "acme-stdlib", "3.24.0-SNAPSHOT")
                .release()
                .stream().platform().registry()
                .clientBuilder()
                // Upstream registry (listed second, like registry.quarkus.io)
                .newRegistry("registry.upstream.test")
                .newPlatform(UPSTREAM_PLATFORM_KEY)
                // Stream "3.23" — same ID as the custom stream
                .newStream("3.23")
                .newRelease("3.23.0")
                .quarkusVersion("3.23.0")
                .addCoreMember()
                .addExtension("io.quarkus", "quarkus-rest", "3.23.0")
                .alignPluginsOnQuarkusVersion()
                .release().stream().platform()
                // Stream "3.27"
                .newStream("3.27")
                .newRelease("3.27.2")
                .quarkusVersion("3.27.2")
                .addCoreMember()
                .addExtension("io.quarkus", "quarkus-rest", "3.27.2")
                .alignPluginsOnQuarkusVersion()
                .release().stream().platform()
                // Stream "3.33"
                .newStream("3.33")
                .newRelease("3.33.1")
                .quarkusVersion("3.33.1")
                .addCoreMember()
                .addExtension("io.quarkus", "quarkus-rest", "3.33.1")
                .alignPluginsOnQuarkusVersion()
                .release()
                .stream().platform().registry()
                .clientBuilder()
                .build();

        enableRegistryClient();
    }

    @Override
    protected String getMainPlatformKey() {
        return UPSTREAM_PLATFORM_KEY;
    }

    @Test
    public void resolveExtensionCatalogWithStreamShouldIncludeCustomExtensions() throws Exception {
        ExtensionCatalogResolver catalogResolver = QuarkusProjectHelper.getCatalogResolver();
        ExtensionCatalog catalog = catalogResolver.resolveExtensionCatalog(
                PlatformStreamCoords.fromString("3.23"));

        List<String> extensionArtifactIds = catalog.getExtensions().stream()
                .map(Extension::getArtifact)
                .map(ArtifactCoords::getArtifactId)
                .toList();

        assertThat(extensionArtifactIds)
                .as("Custom extensions should be found when resolving with -S 3.23")
                .contains("acme-stdlib", "acme-config-yaml");
    }

    @Test
    public void resolveDefaultExtensionCatalogShouldIncludeCustomExtensions() throws Exception {
        ExtensionCatalogResolver catalogResolver = QuarkusProjectHelper.getCatalogResolver();
        ExtensionCatalog catalog = catalogResolver.resolveExtensionCatalog();

        List<String> extensionArtifactIds = catalog.getExtensions().stream()
                .map(Extension::getArtifact)
                .map(ArtifactCoords::getArtifactId)
                .toList();

        assertThat(extensionArtifactIds)
                .as("Custom extensions should appear in default catalog (quarkus ext list)")
                .contains("acme-stdlib", "acme-config-yaml");
    }

    @Test
    public void createProjectWithStreamAndCustomExtension() throws Exception {
        final Path projectDir = newProjectDir("custom-registry-stream-3.23");
        createProject(projectDir,
                PlatformStreamCoords.fromString("3.23"),
                List.of("acme-stdlib", "quarkus-rest"));

        assertModel(projectDir,
                List.of(mainPlatformBom(), ArtifactCoords.pom(CUSTOM_PLATFORM_KEY, "acme-bom", "3.23.0+3331")),
                List.of(ArtifactCoords.jar("com.acme", "acme-stdlib", null),
                        ArtifactCoords.jar("io.quarkus", "quarkus-rest", null)),
                "3.33.1");
    }

    @Test
    public void createProjectWithQualifiedStreamAndCustomExtension() throws Exception {
        final Path projectDir = newProjectDir("custom-registry-qualified-stream-3.23");
        createProject(projectDir,
                PlatformStreamCoords.fromString(CUSTOM_PLATFORM_KEY + ":3.23"),
                List.of("acme-stdlib", "quarkus-rest"));

        assertModel(projectDir,
                List.of(mainPlatformBom(), ArtifactCoords.pom(CUSTOM_PLATFORM_KEY, "acme-bom", "3.23.0+3331")),
                List.of(ArtifactCoords.jar("com.acme", "acme-stdlib", null),
                        ArtifactCoords.jar("io.quarkus", "quarkus-rest", null)),
                "3.33.1");
    }
}
