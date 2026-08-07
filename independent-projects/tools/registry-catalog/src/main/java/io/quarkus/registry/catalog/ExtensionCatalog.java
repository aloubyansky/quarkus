package io.quarkus.registry.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.quarkus.bootstrap.json.JsonObject;
import io.quarkus.bootstrap.json.JsonReader;
import io.quarkus.maven.dependency.ArtifactCoords;

public interface ExtensionCatalog extends ExtensionOrigin {

    String MD_MINIMUM_JAVA_VERSION = "project.properties.minimum-java-version";
    String MD_RECOMMENDED_JAVA_VERSION = "project.properties.recommended-java-version";

    /**
     * All the origins this catalog is derived from.
     *
     * @return all the origins this catalog derives from.
     */
    List<ExtensionOrigin> getDerivedFrom();

    /**
     * Quarkus core version used by the extensions in this catalog.
     *
     * @return Quarkus core version used by the extensions in this catalog
     */
    String getQuarkusCoreVersion();

    /**
     * In case the catalog was built for a custom version of the Quarkus core,
     * this version represents the corresponding upstream community Quarkus core version.
     * This is done to be able to link the custom builds of Quarkus back to the upstream community
     * extensions ecosystem.
     *
     * This method may return null in case the corresponding version does not exist in the upstream community
     * or simply to link back to it.
     *
     *
     * @return the upstream community Quarkus core version corresponding to the Quarkus core version
     *         used in this catalog
     */
    String getUpstreamQuarkusCoreVersion();

    /**
     * Quarkus extensions that constitute the catalog.
     *
     * @return Quarkus extensions that constitute the catalog.
     */
    Collection<Extension> getExtensions();

    /**
     * Extension categories
     *
     * @return extension categories
     */
    List<Category> getCategories();

    /**
     * @return a mutable copy of this configuration
     */
    default ExtensionCatalog.Mutable mutable() {
        return new ExtensionCatalogImpl.Builder(this);
    }

    /**
     * Persist this configuration to the specified file as JSON.
     *
     * @param p target path
     * @throws IOException if the file cannot be written
     */
    default void persist(Path p) throws IOException {
        ExtensionCatalogJsonWriter.serializeExtensionCatalog(this).writeTo(p);
    }

    interface Mutable extends ExtensionCatalog, ExtensionOrigin.Mutable {

        ExtensionCatalog.Mutable setId(String id);

        ExtensionCatalog.Mutable setPlatform(boolean platform);

        ExtensionCatalog.Mutable setBom(ArtifactCoords bom);

        ExtensionCatalog.Mutable setQuarkusCoreVersion(String quarkusCoreVersion);

        ExtensionCatalog.Mutable setUpstreamQuarkusCoreVersion(String upstreamQuarkusVersion);

        ExtensionCatalog.Mutable setDerivedFrom(List<ExtensionOrigin> derivedFrom);

        ExtensionCatalog.Mutable setExtensions(List<Extension> extensions);

        ExtensionCatalog.Mutable addExtension(Extension e);

        ExtensionCatalog.Mutable setCategories(List<Category> categories);

        ExtensionCatalog.Mutable addCategory(Category c);

        ExtensionCatalog.Mutable setMetadata(Map<String, Object> metadata);

        ExtensionCatalog.Mutable setMetadata(String name, Object value);

        ExtensionCatalog.Mutable removeMetadata(String key);

        ExtensionCatalog build();

        default void persist(Path p) throws IOException {
            build().persist(p);
        }
    }

    /**
     * @return a new mutable instance
     */
    static Mutable builder() {
        return new ExtensionCatalogImpl.Builder();
    }

    /**
     * Read an extension catalog from a JSON file.
     *
     * @param path JSON file to read from
     * @return read-only ExtensionCatalog object
     * @throws IOException if the file cannot be read
     */
    static ExtensionCatalog fromFile(Path path) throws IOException {
        return mutableFromFile(path).build();
    }

    /**
     * Read config from a JSON file.
     *
     * @param path JSON file to read from
     * @return mutable ExtensionCatalog object (empty/default for an empty file)
     * @throws IOException if the file cannot be read
     */
    static ExtensionCatalog.Mutable mutableFromFile(Path path) throws IOException {
        JsonObject json = JsonReader.of(Files.readString(path)).read();
        return ExtensionCatalogJsonReader.deserializeMutableExtensionCatalog(json);
    }

    /**
     * Read config from an input stream containing JSON.
     *
     * @param inputStream input stream to read from
     * @return read-only ExtensionCatalog object (empty/default for empty input)
     * @throws IOException if the stream cannot be read
     */
    static ExtensionCatalog fromStream(InputStream inputStream) throws IOException {
        String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        if (content.isEmpty()) {
            return ExtensionCatalog.builder().build();
        }
        JsonObject json = JsonReader.of(content).read();
        return ExtensionCatalogJsonReader.deserializeExtensionCatalog(json);
    }
}
