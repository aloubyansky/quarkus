package io.quarkus.registry.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.json.JsonObject;
import io.quarkus.bootstrap.json.JsonReader;
import io.quarkus.maven.dependency.ArtifactCoords;

class ExtensionCatalogJsonReaderTest {

    static final Path baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            .resolve("src/test/resources/catalog-config");

    @Test
    void deserializeExtensionCatalog() throws IOException {
        String json = Files.readString(baseDir.resolve("extension-catalog.json"));
        JsonObject parsed = JsonReader.of(json).read();
        ExtensionCatalog catalog = ExtensionCatalogJsonReader.readExtensionCatalog(parsed);

        assertThat(catalog.getId()).isEqualTo("io.quarkus:quarkus-fake-bom:999-FAKE:json:999-FAKE");
        assertThat(catalog.isPlatform()).isTrue();
        assertThat(catalog.getBom()).isEqualTo(ArtifactCoords.pom("io.quarkus", "quarkus-bom", "999-FAKE"));
        assertThat(catalog.getQuarkusCoreVersion()).isEqualTo("999-FAKE");

        assertThat(catalog.getExtensions()).hasSize(1);
        Extension ext = catalog.getExtensions().iterator().next();
        assertThat(ext.getName()).isEqualTo("RESTEasy Reactive");
        assertThat(ext.getDescription()).isEqualTo("Description");
        assertThat(ext.getArtifact()).isEqualTo(
                ArtifactCoords.jar("io.quarkus", "quarkus-resteasy-reactive", "999-FAKE"));

        assertThat(ext.getOrigins()).hasSize(1);
        ExtensionOrigin origin = ext.getOrigins().get(0);
        assertThat(origin.getId()).isEqualTo("io.quarkus:quarkus-fake-bom:999-FAKE:json:999-FAKE");
        assertThat(origin.isPlatform()).isTrue();
        assertThat(origin.getBom()).isEqualTo(ArtifactCoords.pom("io.quarkus", "quarkus-bom", "999-FAKE"));

        assertThat(ext.getMetadata()).containsKey("categories");
        assertThat(ext.getMetadata().get("categories")).isEqualTo(Arrays.asList("web", "reactive"));

        assertThat(catalog.getCategories()).hasSize(1);
        Category cat = catalog.getCategories().get(0);
        assertThat(cat.getId()).isEqualTo("web");
        assertThat(cat.getName()).isEqualTo("Web");
        assertThat(cat.getDescription()).isEqualTo("Category description");
        assertThat(cat.getMetadata().get("pinned")).isEqualTo(Arrays.asList("blue", "green", "yellow"));

        Map<String, Object> expectedProject = new HashMap<>();
        expectedProject.computeIfAbsent("properties", k -> {
            Map<String, Object> value = new HashMap<>();
            value.put("doc-root", "https://quarkus.io");
            return value;
        });
        assertThat(catalog.getMetadata().get("project")).isEqualTo(expectedProject);
    }

    @Test
    void deserializeEmptyObject() {
        JsonObject parsed = JsonReader.of("{}").read();
        ExtensionCatalog catalog = ExtensionCatalogJsonReader.readExtensionCatalog(parsed);

        assertThat(catalog.getId()).isNull();
        assertThat(catalog.isPlatform()).isFalse();
        assertThat(catalog.getBom()).isNull();
        assertThat(catalog.getExtensions()).isEmpty();
        assertThat(catalog.getCategories()).isEmpty();
        assertThat(catalog.getDerivedFrom()).isEmpty();
    }

}
