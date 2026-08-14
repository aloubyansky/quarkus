package io.quarkus.registry.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.bootstrap.json.JsonObject;
import io.quarkus.bootstrap.json.JsonReader;
import io.quarkus.registry.json.JsonBuilder;

public interface PlatformCatalog {

    Collection<Platform> getPlatforms();

    Map<String, Object> getMetadata();

    Platform getPlatform(String platformId);

    @JsonIgnore
    default Platform getRecommendedPlatform() {
        final Collection<Platform> platforms = getPlatforms();
        return platforms.isEmpty() ? null : platforms.iterator().next();
    }

    /**
     * @return a mutable copy of this configuration
     */
    default Mutable mutable() {
        return new PlatformCatalogImpl.Builder(this);
    }

    /**
     * Persist this configuration to the specified file as JSON.
     *
     * @param p target path
     * @throws IOException if the file cannot be written
     */
    default void persist(Path p) throws IOException {
        ExtensionCatalogJsonWriter.writePlatformCatalog(this).writeTo(p);
    }

    interface Mutable extends PlatformCatalog, JsonBuilder<PlatformCatalog> {

        Mutable addPlatform(Platform platform);

        Mutable setPlatforms(Collection<Platform> newValues);

        Mutable setMetadata(Map<String, Object> metadata);

        Mutable setMetadata(String key, Object value);

        Mutable removeMetadata(String key);

        PlatformCatalog build();

        default void persist(Path p) throws IOException {
            build().persist(p);
        }
    }

    /**
     * @return a new mutable instance
     */
    static Mutable builder() {
        return new PlatformCatalogImpl.Builder();
    }

    /**
     * Read a platform catalog from a JSON file.
     *
     * @param path JSON file to read from
     * @return read-only PlatformCatalog object
     * @throws IOException if the file cannot be read
     */
    static PlatformCatalog fromFile(Path path) throws IOException {
        return mutableFromFile(path).build();
    }

    /**
     * Read a platform catalog from a JSON file.
     *
     * @param path JSON file to read from
     * @return mutable PlatformCatalog object (empty/default for an empty file)
     * @throws IOException if the file cannot be read
     */
    static PlatformCatalog.Mutable mutableFromFile(Path path) throws IOException {
        JsonObject json = JsonReader.of(Files.readString(path)).read();
        return ExtensionCatalogJsonReader.deserializeMutablePlatformCatalog(json);
    }
}
