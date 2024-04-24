package io.quarkus.devtools.project.create;

import static io.quarkus.devtools.testing.registry.client.TestRegistryClientBuilder.newExtension;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.devtools.testing.registry.client.TestRegistryClientBuilder;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.registry.ExtensionCatalogResolver;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.registry.catalog.ExtensionOrigin;

public class OfferringsTest extends MultiplePlatformBomsTestBase {

    private static final String MAIN_PLATFORM_KEY = "org.acme.platform";

    @BeforeAll
    public static void setup() throws Exception {
        TestRegistryClientBuilder.newInstance()
                //.debug()
                .baseDir(configDir())
                // registry
                .newRegistry("registry.acme.org")
                .enableOfferings("barking")
                // platform key
                .newPlatform(MAIN_PLATFORM_KEY)
                // 2.0 STREAM
                .newStream("2.0")
                // 2.0.4 release
                .newRelease("2.0.4")
                .quarkusVersion("2.2.2")
                // default bom including quarkus-core + essential metadata
                .addCoreMember()
                .addExtension(newExtension("quarkus-dog").addOffering("barking"))
                .addExtension(newExtension("quarkus-cat").addOffering("meow"))
                .addExtension(newExtension("quarkus-dog-cat").addOffering("meow").addOffering("barking"))
                .release()
                // foo platform member
                .newMember("acme-foo-bom")
                .addExtension(newExtension("acme-labrador").addOffering("barking"))
                .release()
                // baz platform
                .newMember("acme-baz-bom")
                .addExtension(newExtension("acme-sphynx").addOffering("meow"))
                .release()
                .registry()
                .clientBuilder()
                .newRegistry("registry.forest.org")
                .newPlatform("org.forest.platform")
                .newStream("1.0")
                .newRelease("1.1.1")
                .quarkusVersion("2.2.2")
                .addCoreMember().release()
                .newMember("forest-birds-bom")
                .addExtension("forest-woodpecker")
                .registry()
                .clientBuilder()
                .build();

        enableRegistryClient();

    }

    protected String getMainPlatformKey() {
        return MAIN_PLATFORM_KEY;
    }

    @Test
    public void barkingOffering() throws Exception {
        var catalog = toExtensionMap(ExtensionCatalogResolver.builder().build().resolveExtensionCatalog());
        assertThat(catalog).isEqualTo(Map.of(
                ArtifactKey.of("org.forest.platform", "forest-woodpecker", ArtifactCoords.DEFAULT_CLASSIFIER,
                        ArtifactCoords.TYPE_JAR),
                Set.of(ArtifactCoords.pom("org.forest.platform", "forest-birds-bom", "1.1.1")),

                ArtifactKey.of("io.quarkus", "quarkus-core", ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_JAR),
                Set.of(ArtifactCoords.pom("org.forest.platform", "quarkus-bom", "1.1.1")),

                ArtifactKey.of("org.acme.platform", "quarkus-dog", ArtifactCoords.DEFAULT_CLASSIFIER, ArtifactCoords.TYPE_JAR),
                Set.of(ArtifactCoords.pom("org.acme.platform", "quarkus-bom", "2.0.4")),

                ArtifactKey.of("org.acme.platform", "quarkus-dog-cat", ArtifactCoords.DEFAULT_CLASSIFIER,
                        ArtifactCoords.TYPE_JAR),
                Set.of(ArtifactCoords.pom("org.acme.platform", "quarkus-bom", "2.0.4")),

                ArtifactKey.of("org.acme.platform", "acme-labrador", ArtifactCoords.DEFAULT_CLASSIFIER,
                        ArtifactCoords.TYPE_JAR),
                Set.of(ArtifactCoords.pom("org.acme.platform", "acme-foo-bom", "2.0.4"))));

    }

    private static Map<ArtifactKey, Set<ArtifactCoords>> toExtensionMap(ExtensionCatalog catalog) {
        final Map<ArtifactKey, Set<ArtifactCoords>> extensionMap = new HashMap<>(catalog.getExtensions().size());
        for (Extension e : catalog.getExtensions()) {
            final Set<ArtifactCoords> boms = new HashSet<>(e.getOrigins().size());
            for (ExtensionOrigin o : e.getOrigins()) {
                boms.add(o.getBom());
            }
            extensionMap.put(e.getArtifact().getKey(), boms);
        }
        return extensionMap;
    }
}
